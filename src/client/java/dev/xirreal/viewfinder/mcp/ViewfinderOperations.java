package dev.xirreal.viewfinder.mcp;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.xirreal.viewfinder.Viewfinder;
import dev.xirreal.viewfinder.compat.MinecraftCompat;
import dev.xirreal.viewfinder.ViewfinderClient;
import dev.xirreal.viewfinder.capture.CaptureStore;
import dev.xirreal.viewfinder.capture.CurrentPass;
import dev.xirreal.viewfinder.capture.DiagnosticsSnapshot;
import dev.xirreal.viewfinder.capture.PassCaptureScheduler;
import dev.xirreal.viewfinder.capture.ProgramRegistry;
import dev.xirreal.viewfinder.capture.SSBODumper;
import dev.xirreal.viewfinder.capture.SSBOInspector;
import dev.xirreal.viewfinder.capture.SSBOResolver;
import dev.xirreal.viewfinder.capture.SafePaths;
import dev.xirreal.viewfinder.capture.TextureDumper;
import dev.xirreal.viewfinder.capture.TextureInspector;
import dev.xirreal.viewfinder.capture.TextureResolver;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.shaderpack.ShaderPack;
import net.irisshaders.iris.shaderpack.include.FileNode;
import net.irisshaders.iris.shaderpack.option.OptionSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

final class ViewfinderOperations {
   private static final Gson GSON = new Gson();
   private static final int MAX_RECOVERY_CHOICES = 64;

   private ViewfinderOperations() {}

   static JsonObject diagnostics() {
      return diagnostics(Map.of());
   }

   static JsonObject diagnostics(Map<String, Object> arguments) {
      boolean includeSamples = bool(arguments, "includeSamples", false);
      return onClient(() -> DiagnosticsSnapshot.create(includeSamples), 10);
   }

   static JsonObject clearDiagnostics() {
      Viewfinder.getErrorCapture().clearErrors();
      return ok("Diagnostics cleared");
   }

   static JsonObject reloadShaders() {
      return onClient(() -> {
         reloadOnClient();
         return withFreshReloadErrors(ok("Shaders reloaded"));
      }, 45);
   }

   static JsonObject setShaderOptions(Map<String, Object> arguments) {
      Map<?, ?> options = objectArgument(arguments, "options", true);
      boolean reset = bool(arguments, "reset", false);
      if (options.isEmpty() && !reset) throw new IllegalArgumentException("options must be non-empty unless reset is true");
      return onClient(() -> {
         try {
            return applyShaderOptions(options, reset);
         } catch (IllegalArgumentException e) {
            return shaderOptionFailure(message(e));
         }
      }, 45);
   }

   static JsonObject listShaderpacks() {
      return onClient(() -> {
         try {
            String current = Iris.getCurrentPackName();
            JsonObject result = ok("Installed shaderpacks listed");
            result.addProperty("current", current);
            result.addProperty("enabled", Iris.getIrisConfig().areShadersEnabled());
            JsonArray packs = new JsonArray();
            for (String name : Iris.getShaderpacksDirectoryManager().enumerate()) {
               JsonObject pack = new JsonObject();
               pack.addProperty("name", name);
               pack.addProperty("selected", Objects.equals(name, current));
               pack.addProperty("type", Files.isDirectory(Iris.getShaderpacksDirectory().resolve(name)) ? "directory" : "archive");
               packs.add(pack);
            }
            result.add("shaderpacks", packs);
            Iris.getCurrentPack().ifPresent(pack -> {
               result.add("config", shaderOptions(pack));
               result.addProperty("profile", pack.getProfileInfo());
            });
            return result;
         } catch (Exception e) {
            throw new IllegalStateException(message(e), e);
         }
      }, 10);
   }

   static JsonObject switchShaderpack(Map<String, Object> arguments) {
      String name = string(arguments, "name", null);
      if (name == null || name.isBlank()) throw new IllegalArgumentException("name is required");
      Map<?, ?> config = objectArgument(arguments, "config", false);
      boolean resetConfig = bool(arguments, "resetConfig", false);
      return onClient(() -> {
         try {
            List<String> installed = Iris.getShaderpacksDirectoryManager().enumerate();
            if (!installed.contains(name)) {
               JsonObject error = failure("Shaderpack is not installed: " + name);
               error.addProperty("installedShaderpackCount", installed.size());
               error.add("installedShaderpacks", strings(installed));
               return error;
            }

            String previous = Iris.getCurrentPackName();
            boolean switched = !Objects.equals(previous, name) || !Iris.getIrisConfig().areShadersEnabled();
            boolean resetDuringSwitch = switched && resetConfig;
            if (switched) {
               Iris.clearShaderPackOptionQueue();
               if (resetDuringSwitch) Iris.resetShaderPackOptionsOnNextReload();
               Iris.getIrisConfig().setShaderPackName(name);
               Iris.getIrisConfig().setShadersEnabled(true);
               Iris.getIrisConfig().save();
               reloadOnClient();
               if (!Objects.equals(name, Iris.getCurrentPackName())) {
                  throw new IllegalStateException("Iris did not load shaderpack " + name);
               }
            }

            JsonObject applied = null;
            if (config != null && !config.isEmpty()) {
               applied = applyShaderOptions(config, resetConfig && !resetDuringSwitch);
               if (resetDuringSwitch) applied.addProperty("reset", true);
            } else if (resetConfig && !resetDuringSwitch) {
               applied = applyShaderOptions(Map.of(), true);
            }
            JsonObject result = ok("Shaderpack loaded: " + name);
            result.addProperty("previous", previous);
            result.addProperty("current", Iris.getCurrentPackName());
            result.addProperty("switched", switched);
            result.addProperty("resetConfig", resetConfig);
            result.add("config", shaderOptions(currentPack()));
            if (applied == null) return switched ? withFreshReloadErrors(result) : result;
            JsonElement errors = applied.remove("errors");
            applied.remove("config");
            result.add("configChange", applied);
            if (!succeeded(applied)) {
               result.addProperty("success", false);
               result.addProperty("message", "Shaderpack loaded, but its config reload failed: " + resultMessage(applied));
               if (errors != null) result.add("errors", errors);
            }
            return result;
         } catch (IllegalArgumentException e) {
            return shaderOptionFailure(message(e));
         } catch (Exception e) {
            throw new IllegalStateException(message(e), e);
         }
      }, 90);
   }

   @SuppressWarnings("unchecked")
   static JsonObject runActions(Map<String, Object> arguments) {
      List<Map<String, Object>> actions = (List<Map<String, Object>>) arguments.get("actions");

      JsonArray actionResults = new JsonArray();
      JsonObject result = new JsonObject();
      result.addProperty("success", true);
      result.addProperty("actionsRequested", actions.size());
      int completed = 0;
      for (int index = 0; index < actions.size(); index++) {
         Map<String, Object> action = actions.get(index);
         String type = string(action, "type", null);
         JsonObject entry = new JsonObject();
         entry.addProperty("index", index);
         entry.addProperty("type", type);
         try {
            JsonObject actionResult = runAction(type, action);
            boolean success = succeeded(actionResult);
            entry.addProperty("success", success);
            entry.add("result", actionResult);
            actionResults.add(entry);
            if (!success) {
               result.addProperty("success", false);
               result.addProperty("failedAction", index);
               result.addProperty("message", "Action " + index + " (" + type + ") failed: " + resultMessage(actionResult));
               break;
            }
            completed++;
         } catch (Exception e) {
            String error = message(e);
            entry.addProperty("success", false);
            entry.addProperty("error", error);
            actionResults.add(entry);
            result.addProperty("success", false);
            result.addProperty("failedAction", index);
            result.addProperty("message", "Action " + index + " (" + type + ") failed: " + error);
            break;
         }
      }
      result.addProperty("actionsCompleted", completed);
      result.add("actionResults", actionResults);
      if (result.get("success").getAsBoolean()) result.addProperty("message", "Completed " + completed + " queued action(s) atomically");
      return result;
   }

   static JsonObject writeShaderSource(Map<String, Object> arguments) {
      String relative = string(arguments, "path", null);
      String source = string(arguments, "source", null);
      if (relative == null || source == null) throw new IllegalArgumentException("path and source are required");
      return writeShaderSources(List.of(new ShaderWrite(relative, source)), bool(arguments, "reload", true));
   }

   static JsonObject writeShaderSources(Map<String, Object> arguments) {
      Object raw = arguments.get("files");
      if (!(raw instanceof List<?> files) || files.isEmpty() || files.size() > ViewfinderToolSchemas.MAX_ACTIONS) {
         throw new IllegalArgumentException("files must contain 1-" + ViewfinderToolSchemas.MAX_ACTIONS + " objects");
      }
      List<ShaderWrite> writes = new ArrayList<>(files.size());
      for (int index = 0; index < files.size(); index++) {
         if (!(files.get(index) instanceof Map<?, ?> file)) throw new IllegalArgumentException("file " + index + " must be an object");
         Object path = file.get("path");
         Object source = file.get("source");
         if (!(path instanceof String relative) || relative.isBlank() || !(source instanceof String text)) {
            throw new IllegalArgumentException("file " + index + " requires a non-empty path and string source");
         }
         writes.add(new ShaderWrite(relative, text));
      }
      return writeShaderSources(writes, bool(arguments, "reload", true));
   }

   private static JsonObject writeShaderSources(List<ShaderWrite> writes, boolean reload) {
      return onClient(() -> {
         try {
            Path root = selectedPackDirectory();
            List<Path> targets = new ArrayList<>(writes.size());
            HashSet<Path> unique = new HashSet<>();
            for (ShaderWrite write : writes) {
               Path target = confinedShaderPath(root, write.path());
               if (!unique.add(target)) throw new IllegalArgumentException("Duplicate shader path: " + write.path());
               targets.add(target);
            }

            JsonArray written = new JsonArray();
            for (int index = 0; index < writes.size(); index++) {
               Files.createDirectories(targets.get(index).getParent());
               Files.writeString(targets.get(index), writes.get(index).source());
               written.add(writes.get(index).path());
            }

            JsonObject result = ok(writes.size() == 1 ? "Shader source written" : writes.size() + " shader sources written");
            result.addProperty("filesWritten", writes.size());
            result.add("paths", written);
            if (writes.size() == 1) result.addProperty("path", writes.get(0).path());
            if (reload) {
               reloadOnClient();
               return withFreshReloadErrors(result);
            }
            return result;
         } catch (Exception e) {
            throw new IllegalStateException(message(e), e);
         }
      }, 45);
   }

   static JsonObject inspectProgram(Map<String, Object> arguments) {
      return onClient(() -> {
         try {
            return ProgramRegistry.inspect(programId(arguments));
         } catch (Exception e) {
            return programFailure(message(e));
         }
      }, 10);
   }

   static JsonObject dumpProgramBinary(Map<String, Object> arguments) {
      return onClient(() -> {
         try {
            int id = programId(arguments);
            JsonObject driver = ProgramRegistry.driverInfo();
            if (!driver.get("programBinaryDumpSupported").getAsBoolean()) {
               JsonObject error = programFailure("Program binary dumps require NVIDIA's proprietary OpenGL driver; active vendor is "
                  + driver.get("vendor").getAsString());
               error.add("driver", driver);
               return error;
            }
            Path capture = CaptureStore.create("program-binary");
            Path output = capture.resolve("program.bin");
            JsonObject result = ProgramRegistry.dumpBinary(id, output);
            result.addProperty("captureId", capture.getFileName().toString());
            result.addProperty("resourceUri", "viewfinder://capture/" + capture.getFileName() + "/" + output.getFileName());
            return result;
         } catch (Exception e) {
            JsonObject error = programFailure(message(e));
            error.add("driver", ProgramRegistry.driverInfo());
            return error;
         }
      }, 15);
   }

   static JsonObject inspectTexture(Map<String, Object> arguments) {
      return onClient(() -> {
         try {
            int samples = integer(arguments, "samples", TextureInspector.defaultSampleCount());
            int x = integer(arguments, "x", -1);
            int y = integer(arguments, "y", -1);
            int z = integer(arguments, "z", 0);
            String name = string(arguments, "name", null);
            JsonObject result = TextureInspector.inspectTexture(textureId(arguments), samples, x, y, z);
            if (name != null) result.addProperty("name", name);
            if (result.has("error")) addTextureChoices(result);
            return result;
         } catch (Exception e) {
            return textureFailure(message(e));
         }
      }, 10);
   }

   static JsonObject dumpTexture(Map<String, Object> arguments) {
      return onClient(() -> {
         try {
            String name = string(arguments, "name", null);
            int id = textureId(arguments);
            boolean raw = bool(arguments, "raw", false);
            Path capture = CaptureStore.create("texture");
            Path output = capture.resolve((name == null ? "texture-" + id : safeName(name)) + (raw ? ".bin" : ".png"));
            JsonObject result = raw ? TextureDumper.dumpTextureRaw(id, output.toString()) : TextureDumper.dumpTexture(id, output.toString());
            result.addProperty("captureId", capture.getFileName().toString());
            result.addProperty("resourceUri", "viewfinder://capture/" + capture.getFileName() + "/" + output.getFileName());
            if (name != null) result.addProperty("name", name);
            return result;
         } catch (Exception e) {
            return textureFailure(message(e));
         }
      }, 15);
   }

   static JsonObject inspectSsbo(Map<String, Object> arguments) {
      return onClient(() -> {
         int index = requiredInteger(arguments, "index");
         int id = SSBOResolver.resolveBufferId(index);
         if (id < 1) return ssboFailure("No SSBO found at index " + index);
         return SSBOInspector.inspectBuffer(id, index, integer(arguments, "bytes", SSBOInspector.defaultPreviewBytes()));
      }, 10);
   }

   static JsonObject dumpSsbo(Map<String, Object> arguments) {
      return onClient(() -> {
         try {
            int index = requiredInteger(arguments, "index");
            int id = SSBOResolver.resolveBufferId(index);
            if (id < 1) return ssboFailure("No SSBO found at index " + index);
            Path capture = CaptureStore.create("ssbo");
            Path output = capture.resolve("ssbo-" + index + ".bin");
            JsonObject result = SSBODumper.dumpBuffer(id, index, output.toString());
            result.addProperty("captureId", capture.getFileName().toString());
            result.addProperty("resourceUri", "viewfinder://capture/" + capture.getFileName() + "/" + output.getFileName());
            return result;
         } catch (Exception e) {
            return ssboFailure(message(e));
         }
      }, 15);
   }

   static JsonObject captureFrame(Map<String, Object> arguments) {
      int frames = integer(arguments, "frames", 1);
      if (frames < 0 || frames > 600) throw new IllegalArgumentException("frames must be between 0 and 600");
      CompletableFuture<String> screenshot = new CompletableFuture<>();
      Minecraft.getInstance().execute(() -> ViewfinderClient.getScreenshotScheduler().scheduleScreenshot(frames, screenshot::complete));
      try {
         Path original = Path.of(screenshot.get(30, TimeUnit.SECONDS));
         Path capture = onClient(() -> {
            try {
               return CaptureStore.create("frame");
            } catch (Exception e) {
               throw new IllegalStateException(e);
            }
         }, 5);
         Path output = capture.resolve(original.getFileName());
         Files.copy(original, output, StandardCopyOption.REPLACE_EXISTING);
         JsonObject result = ok("Frame captured");
         result.addProperty("captureId", capture.getFileName().toString());
         result.addProperty("path", output.toString());
         result.addProperty("resourceUri", "viewfinder://capture/" + capture.getFileName() + "/" + output.getFileName());
         return result;
      } catch (Exception e) {
         throw new IllegalStateException("Frame capture failed: " + message(e), e);
      }
   }

   static JsonObject waitFrames(Map<String, Object> arguments) {
      int frames = integer(arguments, "frames", 1);
      int maxSeconds = integer(arguments, "maxSeconds", 45);
      if (frames < 1 || frames > 600) throw new IllegalArgumentException("frames must be between 1 and 600");
      if (maxSeconds < 5 || maxSeconds > 110) throw new IllegalArgumentException("maxSeconds must be between 5 and 110");
      CompletableFuture<Void> wait = onClient(() -> ViewfinderClient.getScreenshotScheduler().waitForFrames(frames), 5);
      try {
         wait.get(maxSeconds, TimeUnit.SECONDS);
         JsonObject result = ok("Waited " + frames + " rendered frame(s)");
         result.addProperty("frames", frames);
         return result;
      } catch (Exception e) {
         onClient(() -> {
            ViewfinderClient.getScreenshotScheduler().cancelFrameWait(wait);
            return null;
         }, 5);
         throw new IllegalStateException("Frame wait failed: " + message(e), e);
      }
   }

   static JsonObject profileFrames(Map<String, Object> arguments) {
      int frames = integer(arguments, "frames", 60);
      int maxSeconds = integer(arguments, "maxSeconds", 45);
      boolean includeSamples = bool(arguments, "includeSamples", false);
      if (maxSeconds < 5 || maxSeconds > 110) throw new IllegalArgumentException("maxSeconds must be between 5 and 110");
      CompletableFuture<JsonObject> capture = onClient(() -> Viewfinder.getMetricsCollector().captureFrames(frames, includeSamples), 5);
      try {
         JsonObject result = new JsonObject();
         result.addProperty("frames", frames);
         result.addProperty("framesCaptured", frames);
         result.addProperty("complete", true);
         result.addProperty("includeSamples", includeSamples);
         JsonObject passes = capture.get(maxSeconds, TimeUnit.SECONDS);
         result.add("passes", passes);
         result.addProperty("message", profileSummary(frames, frames, passes, true));
         return result;
      } catch (TimeoutException e) {
         JsonObject partial = onClient(() -> Viewfinder.getMetricsCollector().finishCapture(), 5);
         int captured = partial.get("framesCaptured").getAsInt();
         JsonObject passes = partial.getAsJsonObject("passes");
         JsonObject result = new JsonObject();
         result.addProperty("frames", frames);
         result.addProperty("framesCaptured", captured);
         result.addProperty("complete", false);
         result.addProperty("deadlineSeconds", maxSeconds);
         result.addProperty("includeSamples", includeSamples);
         result.add("passes", passes);
         result.addProperty("message", profileSummary(frames, captured, passes, false));
         return result;
      } catch (Exception e) {
         capture.cancel(false);
         onClient(() -> Viewfinder.getMetricsCollector().finishCapture(), 5);
         throw new IllegalStateException("Profile capture failed: " + message(e), e);
      }
   }

   static JsonObject capturePassOutputs(Map<String, Object> arguments) {
      CompletableFuture<JsonObject> capture = onClient(() -> PassCaptureScheduler.schedule(string(arguments, "pass", null)), 5);
      try {
         return capture.get(integer(arguments, "timeoutSeconds", 15), TimeUnit.SECONDS);
      } catch (TimeoutException e) {
         capture.cancel(false);
         JsonObject error = failure("Timed out waiting for pass: " + string(arguments, "pass", null));
         JsonArray observed = CurrentPass.pipelineJson().getAsJsonArray("observedPasses");
         error.addProperty("observedPassCount", observed.size());
         error.add("observedPasses", limited(observed));
         return error;
      } catch (Exception e) {
         capture.cancel(false);
         throw new IllegalStateException("Pass capture failed: " + message(e), e);
      }
   }

   static JsonObject getRenderSettings() {
      return onClient(() -> {
         JsonObject result = ok("Render settings queried");
         result.add("settings", DiagnosticsSnapshot.renderSettings(Minecraft.getInstance()));
         return result;
      }, 5);
   }

   static JsonObject setRenderSettings(Map<String, Object> arguments) {
      Integer distance = arguments.containsKey("renderDistance") ? requiredInteger(arguments, "renderDistance") : null;
      Integer fov = arguments.containsKey("fov") ? requiredInteger(arguments, "fov") : null;
      if (distance == null && fov == null) throw new IllegalArgumentException("renderDistance or fov is required");
      return onClient(() -> {
         Minecraft minecraft = Minecraft.getInstance();
         var options = minecraft.options;
         if (distance != null) validateRenderSetting("renderDistance", options.renderDistance().values(), distance);
         if (fov != null) validateRenderSetting("fov", options.fov().values(), fov);

         if (distance != null) options.renderDistance().set(distance);
         if (fov != null) options.fov().set(fov);
         options.save();

         JsonObject result = ok("Render settings updated");
         result.add("settings", DiagnosticsSnapshot.renderSettings(minecraft));
         return result;
      }, 10);
   }

   static void validateRenderSetting(String name, OptionInstance.ValueSet<Integer> values, int value) {
      if (values.validateValue(value).filter(validated -> validated == value).isEmpty()) {
         throw new IllegalArgumentException(name + " value " + value + " is unsupported by this client");
      }
   }

   static JsonObject setScene(Map<String, Object> arguments) {
      MinecraftServer server = onClient(() -> Minecraft.getInstance().getSingleplayerServer(), 5);
      if (server == null) throw new IllegalStateException("Scene mutation requires an open singleplayer world");
      var playerId = onClient(() -> {
         if (Minecraft.getInstance().player == null) throw new IllegalStateException("No local player");
         return Minecraft.getInstance().player.getUUID();
      }, 5);
      return onServer(server, () -> {
         ServerPlayer player = server.getPlayerList().getPlayer(playerId);
         if (player == null) throw new IllegalStateException("Local server player is unavailable");
         double x = number(arguments, "x", player.getX());
         double y = number(arguments, "y", player.getY());
         double z = number(arguments, "z", player.getZ());
         float yaw = (float) number(arguments, "yaw", player.getYRot());
         float pitch = (float) number(arguments, "pitch", player.getXRot());
         player.teleportTo(player.level(), x, y, z, Set.of(), yaw, pitch, false);

         if (arguments.containsKey("time")) {
            player.level().dimensionType().defaultClock().ifPresent(clock ->
               server.clockManager().setTotalTicks(clock, ((Number) arguments.get("time")).longValue()));
         }
         String weather = string(arguments, "weather", null);
         if (weather != null) {
            switch (weather) {
               case "clear" -> server.setWeatherParameters(6000, 0, false, false);
               case "rain" -> server.setWeatherParameters(0, 6000, true, false);
               case "thunder" -> server.setWeatherParameters(0, 6000, true, true);
               default -> throw new IllegalArgumentException("weather must be clear, rain, or thunder");
            }
         }
         JsonObject result = ok("Singleplayer scene updated");
         result.addProperty("x", x);
         result.addProperty("y", y);
         result.addProperty("z", z);
         result.addProperty("yaw", yaw);
         result.addProperty("pitch", pitch);
         return result;
      });
   }

   static JsonObject controlTicks(Map<String, Object> arguments) {
      String action = string(arguments, "action", null);
      MinecraftServer server = onClient(() -> Minecraft.getInstance().getSingleplayerServer(), 5);
      if (server == null) throw new IllegalStateException("Tick control requires an open singleplayer world");
      return onServer(server, () -> {
         switch (action == null ? "" : action) {
            case "freeze" -> server.tickRateManager().setFrozen(true);
            case "resume" -> server.tickRateManager().setFrozen(false);
            case "step" -> {
               server.tickRateManager().setFrozen(true);
               if (!server.tickRateManager().stepGameIfPaused(integer(arguments, "ticks", 1))) {
                  throw new IllegalStateException("Server could not step while paused");
               }
            }
            default -> throw new IllegalArgumentException("action must be freeze, resume, or step");
         }
         if (arguments.containsKey("tickRate")) server.tickRateManager().setTickRate((float) number(arguments, "tickRate", 20));
         return ok("Tick control applied: " + action);
      });
   }

   static JsonObject shaderpackManifest() {
      return onClient(() -> {
         ShaderPack pack = currentPack();
         JsonObject result = new JsonObject();
         result.addProperty("name", Iris.getCurrentPackName());
         result.addProperty("sourceMutable", Files.isDirectory(Iris.getShaderpacksDirectory().resolve(Iris.getCurrentPackName())));
         result.addProperty("profile", pack.getProfileInfo());
         result.add("options", shaderOptions(pack));
         JsonArray sources = new JsonArray();
         pack.getShaderPackOptions().getIncludes().getNodes().keySet().forEach(path -> sources.add(path.getPathString()));
         result.add("sources", sources);
         JsonArray includeFailures = new JsonArray();
         pack.getShaderPackOptions().getIncludes().getFailures().forEach((path, failure) -> {
            JsonObject entry = new JsonObject();
            entry.addProperty("path", path.toString());
            entry.addProperty("error", failure.toString());
            includeFailures.add(entry);
         });
         result.add("includeFailures", includeFailures);
         return result;
      }, 10);
   }

   static JsonObject pipeline() {
      return onClient(CurrentPass::pipelineJson, 5);
   }

   static String shaderSource(String relative) {
      return onClient(() -> {
         String wanted = relative.startsWith("/") ? relative : "/" + relative;
         Optional<FileNode> node = currentPack().getShaderPackOptions().getIncludes().getNodes().entrySet().stream()
            .filter(entry -> entry.getKey().getPathString().equals(wanted))
            .map(Map.Entry::getValue).findFirst();
         return node.map(file -> String.join("\n", file.getLines()))
            .orElseThrow(() -> new IllegalArgumentException("Shader source not found: " + relative));
      }, 10);
   }

   static String patchedShader(String name) {
      try {
         Path root = Minecraft.getInstance().gameDirectory.toPath().resolve("patched_shaders").toAbsolutePath().normalize();
         Path file = root.resolve(name).normalize();
         if (!file.startsWith(root) || !Files.isRegularFile(file) || !file.toRealPath().startsWith(root.toRealPath())) {
            throw new IllegalArgumentException("Patched shader not found");
         }
         return Files.readString(file);
      } catch (Exception e) {
         throw new IllegalStateException(message(e), e);
      }
   }

   static Path captureFile(String captureId, String file) {
      return onClient(() -> CaptureStore.resolve(captureId, file), 5);
   }

   static Object structured(JsonObject object) {
      return GSON.fromJson(object, Object.class);
   }

   private static JsonObject runAction(String type, Map<String, Object> arguments) {
      return switch (type) {
         case "get_diagnostics" -> diagnostics(arguments);
         case "clear_diagnostics" -> clearDiagnostics();
         case "reload_shaders" -> reloadShaders();
         case "list_shaderpacks" -> listShaderpacks();
         case "switch_shaderpack" -> switchShaderpack(arguments);
         case "set_shader_options" -> setShaderOptions(arguments);
         case "write_shader_source" -> writeShaderSource(arguments);
         case "write_shader_sources" -> writeShaderSources(arguments);
         case "wait_frames" -> waitFrames(arguments);
         case "capture_frame" -> captureFrame(arguments);
         case "profile_frames" -> profileFrames(arguments);
         case "capture_pass_outputs" -> capturePassOutputs(arguments);
         case "list_programs" -> onClient(ProgramRegistry::listPrograms, 5);
         case "inspect_program" -> inspectProgram(arguments);
         case "dump_program_binary" -> dumpProgramBinary(arguments);
         case "list_textures" -> onClient(TextureResolver::listTextures, 5);
         case "inspect_texture" -> inspectTexture(arguments);
         case "dump_texture" -> dumpTexture(arguments);
         case "list_ssbos" -> onClient(SSBOResolver::listBuffers, 5);
         case "inspect_ssbo" -> inspectSsbo(arguments);
         case "dump_ssbo" -> dumpSsbo(arguments);
         case "get_render_settings" -> getRenderSettings();
         case "set_render_settings" -> setRenderSettings(arguments);
         case "set_scene" -> setScene(arguments);
         case "control_ticks" -> controlTicks(arguments);
         default -> throw new IllegalArgumentException("Unsupported action type: " + type);
      };
   }

   private static JsonObject applyShaderOptions(Map<?, ?> options, boolean reset) {
      ShaderPack pack = currentPack();
      OptionSet optionSet = pack.getShaderPackOptions().getOptionSet();
      Map<String, String> validated = new LinkedHashMap<>();
      JsonObject changed = new JsonObject();
      for (Map.Entry<?, ?> entry : options.entrySet()) {
         if (!(entry.getKey() instanceof String name) || name.isBlank()) {
            throw new IllegalArgumentException("Shader option names must be non-empty strings");
         }
         Object rawValue = entry.getValue();
         if (rawValue == null || rawValue instanceof Map<?, ?> || rawValue instanceof Iterable<?>) {
            throw new IllegalArgumentException("Shader option " + name + " must have a boolean, number, or string value");
         }
         String value = String.valueOf(rawValue);
         if (!optionSet.getBooleanOptions().containsKey(name) && !optionSet.getStringOptions().containsKey(name)) {
            List<String> available = new ArrayList<>(optionSet.getBooleanOptions().keySet());
            available.addAll(optionSet.getStringOptions().keySet());
            throw new IllegalArgumentException("Unknown shader option: " + name + ". Available options: " + choiceSummary(available));
         }
         if (optionSet.getBooleanOptions().containsKey(name) && !(value.equalsIgnoreCase("true") || value.equalsIgnoreCase("false"))) {
            throw new IllegalArgumentException(name + " must be true or false");
         }
         if (optionSet.getStringOptions().containsKey(name)
            && !optionSet.getStringOptions().get(name).getOption().getAllowedValues().contains(value)) {
            throw new IllegalArgumentException("Invalid value for " + name + ": " + value + ". Allowed values: "
               + choiceSummary(optionSet.getStringOptions().get(name).getOption().getAllowedValues()));
         }
         validated.put(name, value);
         changed.addProperty(name, value);
      }

      Iris.clearShaderPackOptionQueue();
      if (reset) {
         // Iris clears queued overrides after a reset, so defaults and explicit values need separate reloads.
         Iris.resetShaderPackOptionsOnNextReload();
         reloadOnClient();
      }
      if (!validated.isEmpty()) {
         Iris.getShaderPackOptionQueue().putAll(validated);
         reloadOnClient();
      }
      JsonObject result = ok(reset ? "Shader config reset and shaders reloaded" : "Shader options updated and shaders reloaded");
      result.addProperty("reset", reset);
      result.add("changed", changed);
      result.add("config", shaderOptions(currentPack()));
      return withFreshReloadErrors(result);
   }

   private static JsonObject withFreshReloadErrors(JsonObject result) {
      JsonArray errors = Viewfinder.getErrorCapture().toJson();
      if (errors.size() == 0) return result;
      result.addProperty("success", false);
      result.addProperty("message", result.get("message").getAsString() + "; reload reported "
         + errors.size() + " fresh error(s)");
      result.add("errors", errors);
      return result;
   }

   private static JsonObject shaderOptions(ShaderPack pack) {
      JsonObject options = new JsonObject();
      pack.getShaderPackOptions().getOptionSet().getBooleanOptions().forEach((name, option) ->
         options.addProperty(name, pack.getShaderPackOptions().getOptionValues().getBooleanValueOrDefault(name)));
      pack.getShaderPackOptions().getOptionSet().getStringOptions().forEach((name, option) ->
         options.addProperty(name, pack.getShaderPackOptions().getOptionValues().getStringValueOrDefault(name)));
      return options;
   }

   private static Map<?, ?> objectArgument(Map<String, Object> arguments, String name, boolean required) {
      Object raw = arguments.get(name);
      if (raw == null && !required) return null;
      if (!(raw instanceof Map<?, ?> object)) throw new IllegalArgumentException(name + " must be an object");
      return object;
   }

   static boolean succeeded(JsonObject result) {
      return !result.has("error") && (!result.has("success") || result.get("success").getAsBoolean());
   }

   private static String resultMessage(JsonObject result) {
      if (result.has("message")) return result.get("message").getAsString();
      if (result.has("error")) return result.get("error").getAsString();
      return "operation reported failure";
   }

   private static String profileSummary(int requested, int captured, JsonObject passes, boolean complete) {
      String slowest = null;
      long slowestNanos = -1;
      for (Map.Entry<String, JsonElement> entry : passes.entrySet()) {
         JsonElement timing = entry.getValue();
         if (!timing.isJsonObject() || !timing.getAsJsonObject().has("latest")) continue;
         long latest = timing.getAsJsonObject().get("latest").getAsLong();
         if (latest > slowestNanos) {
            slowestNanos = latest;
            slowest = entry.getKey();
         }
      }
      String prefix = complete ? "Profiled " + captured + " frame(s)" : "Profile deadline reached; captured " + captured + "/" + requested + " frame(s)";
      if (slowest == null) return prefix + "; no completed pass timings yet";
      return prefix + " across " + passes.size() + " pass(es); slowest "
         + slowest + " (" + String.format(java.util.Locale.ROOT, "%.2f", slowestNanos / 1_000_000.0) + " ms)";
   }

   private static ShaderPack currentPack() {
      return Iris.getCurrentPack().orElseThrow(() -> new IllegalStateException("No shaderpack is loaded"));
   }

   private static Path selectedPackDirectory() throws Exception {
      Path selected = Iris.getShaderpacksDirectory().resolve(Iris.getCurrentPackName());
      if (!Files.isDirectory(selected)) throw new IllegalStateException("Source mutation is only available for directory shaderpacks");
      return selected.toRealPath();
   }

   static Path confinedShaderPath(Path root, String relative) throws Exception {
      if (relative.isBlank() || Path.of(relative).isAbsolute()) throw new IllegalArgumentException("Shader path must be relative");
      Path target = SafePaths.resolve(root, relative);
      Path shaders = root.resolve("shaders").toRealPath();
      if (!target.startsWith(shaders)) {
         throw new IllegalArgumentException("Shader path must stay inside the pack's shaders directory");
      }
      Path ancestor = target;
      while (!Files.exists(ancestor)) ancestor = ancestor.getParent();
      if (!ancestor.toRealPath().startsWith(shaders)) throw new IllegalArgumentException("Shader path crosses a symbolic link outside shaders");
      return target;
   }

   private static <T> T onClient(Supplier<T> operation, int timeoutSeconds) {
      Minecraft minecraft = Minecraft.getInstance();
      if (minecraft.isSameThread()) return operation.get();
      CompletableFuture<T> result = new CompletableFuture<>();
      minecraft.execute(() -> {
         try {
            result.complete(operation.get());
         } catch (Throwable e) {
            result.completeExceptionally(e);
         }
      });
      try {
         return result.get(timeoutSeconds, TimeUnit.SECONDS);
      } catch (Exception e) {
         throw new IllegalStateException(message(e), e);
      }
   }

   private static <T> T onServer(MinecraftServer server, Supplier<T> operation) {
      if (server.isSameThread()) return operation.get();
      CompletableFuture<T> result = new CompletableFuture<>();
      server.execute(() -> {
         try {
            result.complete(operation.get());
         } catch (Throwable e) {
            result.completeExceptionally(e);
         }
      });
      try {
         return result.get(10, TimeUnit.SECONDS);
      } catch (Exception e) {
         throw new IllegalStateException(message(e), e);
      }
   }

   private static JsonObject ok(String message) {
      JsonObject result = new JsonObject();
      result.addProperty("success", true);
      result.addProperty("message", message);
      return result;
   }

   private static JsonObject failure(String message) {
      JsonObject result = new JsonObject();
      result.addProperty("success", false);
      result.addProperty("error", message);
      return result;
   }

   private static JsonObject programFailure(String message) {
      JsonObject result = failure(message);
      JsonArray programs = ProgramRegistry.listPrograms().getAsJsonArray("programs");
      result.addProperty("availableProgramCount", programs.size());
      result.add("availablePrograms", limited(programs));
      return result;
   }

   private static JsonObject textureFailure(String message) {
      JsonObject result = failure(message);
      addTextureChoices(result);
      return result;
   }

   private static JsonObject ssboFailure(String message) {
      JsonObject result = failure(message);
      JsonArray buffers = SSBOResolver.listBuffers().getAsJsonArray("buffers");
      result.addProperty("availableSsboCount", buffers.size());
      result.add("availableSsbos", limited(buffers));
      return result;
   }

   private static JsonObject shaderOptionFailure(String message) {
      JsonObject result = failure(message);
      try {
         OptionSet options = currentPack().getShaderPackOptions().getOptionSet();
         JsonObject available = new JsonObject();
         int count = 0;
         for (String name : options.getBooleanOptions().keySet()) {
            if (count++ == MAX_RECOVERY_CHOICES) break;
            JsonObject option = new JsonObject();
            option.addProperty("type", "boolean");
            option.add("allowedValues", GSON.toJsonTree(List.of(true, false)));
            available.add(name, option);
         }
         if (count < MAX_RECOVERY_CHOICES) {
            for (Map.Entry<String, ?> entry : options.getStringOptions().entrySet()) {
               if (count++ == MAX_RECOVERY_CHOICES) break;
               JsonObject option = new JsonObject();
               option.addProperty("type", "string");
               option.add("allowedValues", GSON.toJsonTree(options.getStringOptions().get(entry.getKey()).getOption().getAllowedValues()));
               available.add(entry.getKey(), option);
            }
         }
         result.addProperty("availableOptionCount", options.getBooleanOptions().size() + options.getStringOptions().size());
         result.add("availableOptions", available);
      } catch (Exception ignored) {
         // The original validation error remains useful if no pack is active.
      }
      return result;
   }

   private static void addTextureChoices(JsonObject result) {
      result.add("availableTextures", TextureResolver.listTextures());
   }

   private static JsonArray strings(Iterable<?> values) {
      JsonArray result = new JsonArray();
      for (Object value : values) {
         if (result.size() == MAX_RECOVERY_CHOICES) break;
         result.add(String.valueOf(value));
      }
      return result;
   }

   private static JsonArray limited(JsonArray values) {
      JsonArray result = new JsonArray();
      for (int index = 0; index < Math.min(values.size(), MAX_RECOVERY_CHOICES); index++) result.add(values.get(index));
      return result;
   }

   private static String choiceSummary(Iterable<?> values) {
      List<String> choices = new ArrayList<>();
      int count = 0;
      for (Object value : values) {
         if (count++ < MAX_RECOVERY_CHOICES) choices.add(String.valueOf(value));
      }
      String result = String.join(", ", choices);
      return count > MAX_RECOVERY_CHOICES ? result + " (and " + (count - MAX_RECOVERY_CHOICES) + " more)" : result;
   }

   private static String string(Map<String, Object> values, String key, String fallback) {
      Object value = values.get(key);
      return value == null ? fallback : String.valueOf(value);
   }

   private static boolean bool(Map<String, Object> values, String key, boolean fallback) {
      Object value = values.get(key);
      return value instanceof Boolean b ? b : value == null ? fallback : Boolean.parseBoolean(String.valueOf(value));
   }

   private static int integer(Map<String, Object> values, String key, int fallback) {
      Object value = values.get(key);
      return value instanceof Number n ? n.intValue() : value == null ? fallback : Integer.parseInt(String.valueOf(value));
   }

   private static int requiredInteger(Map<String, Object> values, String key) {
      Object value = values.get(key);
      if (!(value instanceof Number number)) throw new IllegalArgumentException(key + " is required and must be an integer");
      double decimal = number.doubleValue();
      if (!Double.isFinite(decimal) || decimal != Math.rint(decimal) || decimal < Integer.MIN_VALUE || decimal > Integer.MAX_VALUE) {
         throw new IllegalArgumentException(key + " must be an integer");
      }
      return number.intValue();
   }

   static int programId(Map<String, Object> arguments) {
      boolean hasName = arguments.containsKey("name");
      boolean hasId = arguments.containsKey("id");
      if (hasName == hasId) throw new IllegalArgumentException("Exactly one of program name or id is required");
      if (hasName) return ProgramRegistry.resolve(string(arguments, "name", null));
      int id = requiredInteger(arguments, "id");
      if (id < 1) throw new IllegalArgumentException("Program id must be positive");
      return id;
   }

   static int textureId(Map<String, Object> arguments) {
      boolean hasName = arguments.containsKey("name");
      boolean hasId = arguments.containsKey("id");
      if (hasName == hasId) throw new IllegalArgumentException("Exactly one of texture name or id is required");
      if (hasName) {
         String name = string(arguments, "name", null);
         if (name == null || name.isBlank()) throw new IllegalArgumentException("Texture name must be non-empty");
         int id = TextureResolver.resolveTexture(name);
         if (id < 1) throw new IllegalArgumentException("Unknown Iris texture name: " + name);
         return id;
      }
      int id = requiredInteger(arguments, "id");
      if (id < 1) throw new IllegalArgumentException("Texture id must be positive");
      return id;
   }

   private static double number(Map<String, Object> values, String key, double fallback) {
      Object value = values.get(key);
      return value instanceof Number n ? n.doubleValue() : value == null ? fallback : Double.parseDouble(String.valueOf(value));
   }

   private static String safeName(String value) {
      return value.replaceAll("[^a-zA-Z0-9._-]", "_");
   }

   private static void reloadOnClient() {
      Minecraft minecraft = Minecraft.getInstance();
      closeIrisErrorScreen(minecraft);
      try {
         Iris.reload();
      } catch (Exception e) {
         throw new IllegalStateException(message(e), e);
      } finally {
         closeIrisErrorScreen(minecraft);
      }
   }

   private static void closeIrisErrorScreen(Minecraft minecraft) {
      Screen screen = MinecraftCompat.screen(minecraft);
      if (screen != null && screen.getClass().getName().equals("net.irisshaders.iris.gui.debug.DebugLoadFailedGridScreen")) MinecraftCompat.setScreen(minecraft, null);
   }

   private static String message(Throwable error) {
      Throwable cause = error;
      while (cause.getCause() != null) cause = cause.getCause();
      return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
   }

   private record ShaderWrite(String path, String source) {}
}
