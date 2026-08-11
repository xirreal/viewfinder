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
import java.util.Map;
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
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

final class ViewfinderOperations {
   private static final Gson GSON = new Gson();

   private ViewfinderOperations() {}

   static JsonObject diagnostics() {
      return onClient(DiagnosticsSnapshot::create, 10);
   }

   static JsonObject clearDiagnostics() {
      Viewfinder.getErrorCapture().clearErrors();
      return ok("Diagnostics cleared");
   }

   static JsonObject reloadShaders() {
      return onClient(() -> {
         Minecraft minecraft = Minecraft.getInstance();
         closeIrisErrorScreen(minecraft);
         try {
            Iris.reload();
         } catch (Exception e) {
            throw new IllegalStateException(message(e), e);
         } finally {
            closeIrisErrorScreen(minecraft);
         }
         JsonObject result = ok("Shaders reloaded");
         result.add("diagnostics", DiagnosticsSnapshot.create());
         return result;
      }, 45);
   }

   static JsonObject setShaderOptions(Map<String, Object> arguments) {
      Object raw = arguments.get("options");
      if (!(raw instanceof Map<?, ?> options) || options.isEmpty()) throw new IllegalArgumentException("options must be a non-empty object");
      return onClient(() -> {
         ShaderPack pack = currentPack();
         OptionSet optionSet = pack.getShaderPackOptions().getOptionSet();
         JsonObject changed = new JsonObject();
         for (Map.Entry<?, ?> entry : options.entrySet()) {
            String name = String.valueOf(entry.getKey());
            String value = String.valueOf(entry.getValue());
            if (!optionSet.getBooleanOptions().containsKey(name) && !optionSet.getStringOptions().containsKey(name)) {
               throw new IllegalArgumentException("Unknown shader option: " + name);
            }
            if (optionSet.getBooleanOptions().containsKey(name) && !(value.equalsIgnoreCase("true") || value.equalsIgnoreCase("false"))) {
               throw new IllegalArgumentException(name + " must be true or false");
            }
            if (optionSet.getStringOptions().containsKey(name)
               && !optionSet.getStringOptions().get(name).getOption().getAllowedValues().contains(value)) {
               throw new IllegalArgumentException("Invalid value for " + name + ": " + value);
            }
            Iris.getShaderPackOptionQueue().put(name, value);
            changed.addProperty(name, value);
         }
         try {
            Iris.reload();
         } catch (Exception e) {
            throw new IllegalStateException(message(e), e);
         }
         JsonObject result = ok("Shader options updated and shaders reloaded");
         result.add("changed", changed);
         result.add("errors", Viewfinder.getErrorCapture().toJson());
         return result;
      }, 45);
   }

   static JsonObject writeShaderSource(Map<String, Object> arguments) {
      String relative = string(arguments, "path", null);
      String source = string(arguments, "source", null);
      if (relative == null || source == null) throw new IllegalArgumentException("path and source are required");
      boolean reload = bool(arguments, "reload", true);
      return onClient(() -> {
         try {
            Path root = selectedPackDirectory();
            Path target = confinedShaderPath(root, relative);
            Files.createDirectories(target.getParent());
            Files.writeString(target, source);
            JsonObject result = ok("Shader source written");
            result.addProperty("path", relative);
            if (reload) {
               Iris.reload();
               result.add("errors", Viewfinder.getErrorCapture().toJson());
            }
            return result;
         } catch (Exception e) {
            throw new IllegalStateException(message(e), e);
         }
      }, 45);
   }

   static JsonObject inspectProgram(Map<String, Object> arguments) {
      Integer id = arguments.containsKey("id") ? integer(arguments, "id", 0) : null;
      return onClient(() -> ProgramRegistry.inspect(id), 10);
   }

   static JsonObject dumpProgramBinary(Map<String, Object> arguments) {
      Integer id = arguments.containsKey("id") ? integer(arguments, "id", 0) : null;
      return onClient(() -> {
         try {
            Path capture = CaptureStore.create("program-binary");
            Path output = capture.resolve("program.bin");
            JsonObject result = ProgramRegistry.dumpBinary(id, output);
            result.addProperty("captureId", capture.getFileName().toString());
            result.addProperty("resourceUri", "viewfinder://capture/" + capture.getFileName() + "/" + output.getFileName());
            return result;
         } catch (Exception e) {
            throw new IllegalStateException(message(e), e);
         }
      }, 15);
   }

   static JsonObject inspectTexture(Map<String, Object> arguments) {
      return onClient(() -> {
         int samples = integer(arguments, "samples", TextureInspector.defaultSampleCount());
         int x = integer(arguments, "x", -1);
         int y = integer(arguments, "y", -1);
         int z = integer(arguments, "z", 0);
         String name = string(arguments, "name", null);
         return name != null ? TextureInspector.inspectByName(name, samples, x, y, z)
            : TextureInspector.inspectTexture(integer(arguments, "id", 0), samples, x, y, z);
      }, 10);
   }

   static JsonObject dumpTexture(Map<String, Object> arguments) {
      return onClient(() -> {
         try {
            String name = string(arguments, "name", null);
            int id = name == null ? integer(arguments, "id", 0) : TextureResolver.resolveTexture(name);
            if (id < 1) throw new IllegalArgumentException("Texture not found");
            boolean raw = bool(arguments, "raw", false);
            Path capture = CaptureStore.create("texture");
            Path output = capture.resolve((name == null ? "texture-" + id : safeName(name)) + (raw ? ".bin" : ".png"));
            JsonObject result = raw ? TextureDumper.dumpTextureRaw(id, output.toString()) : TextureDumper.dumpTexture(id, output.toString());
            result.addProperty("captureId", capture.getFileName().toString());
            result.addProperty("resourceUri", "viewfinder://capture/" + capture.getFileName() + "/" + output.getFileName());
            return result;
         } catch (Exception e) {
            throw new IllegalStateException(message(e), e);
         }
      }, 15);
   }

   static JsonObject inspectSsbo(Map<String, Object> arguments) {
      return onClient(() -> SSBOInspector.inspectIndex(integer(arguments, "index", 0),
         integer(arguments, "bytes", SSBOInspector.defaultPreviewBytes())), 10);
   }

   static JsonObject dumpSsbo(Map<String, Object> arguments) {
      return onClient(() -> {
         try {
            int index = integer(arguments, "index", 0);
            int id = SSBOResolver.resolveBufferId(index);
            if (id < 1) throw new IllegalArgumentException("No SSBO found at index " + index);
            Path capture = CaptureStore.create("ssbo");
            Path output = capture.resolve("ssbo-" + index + ".bin");
            JsonObject result = SSBODumper.dumpBuffer(id, index, output.toString());
            result.addProperty("captureId", capture.getFileName().toString());
            result.addProperty("resourceUri", "viewfinder://capture/" + capture.getFileName() + "/" + output.getFileName());
            return result;
         } catch (Exception e) {
            throw new IllegalStateException(message(e), e);
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

   static JsonObject profileFrames(Map<String, Object> arguments) {
      int frames = integer(arguments, "frames", 60);
      int maxSeconds = integer(arguments, "maxSeconds", 45);
      if (maxSeconds < 5 || maxSeconds > 110) throw new IllegalArgumentException("maxSeconds must be between 5 and 110");
      CompletableFuture<JsonObject> capture = onClient(() -> Viewfinder.getMetricsCollector().captureFrames(frames), 5);
      try {
         JsonObject result = new JsonObject();
         result.addProperty("frames", frames);
         result.addProperty("framesCaptured", frames);
         result.addProperty("complete", true);
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
      } catch (Exception e) {
         capture.cancel(false);
         throw new IllegalStateException("Pass capture failed: " + message(e), e);
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
         JsonObject options = new JsonObject();
         pack.getShaderPackOptions().getOptionSet().getBooleanOptions().forEach((name, option) ->
            options.addProperty(name, pack.getShaderPackOptions().getOptionValues().getBooleanValueOrDefault(name)));
         pack.getShaderPackOptions().getOptionSet().getStringOptions().forEach((name, option) ->
            options.addProperty(name, pack.getShaderPackOptions().getOptionValues().getStringValueOrDefault(name)));
         result.add("options", options);
         JsonArray sources = new JsonArray();
         pack.getShaderPackOptions().getIncludes().getNodes().keySet().forEach(path -> sources.add(path.toString()));
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
            .filter(entry -> entry.getKey().toString().equals(wanted) || entry.getKey().toString().equals(relative))
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

   private static double number(Map<String, Object> values, String key, double fallback) {
      Object value = values.get(key);
      return value instanceof Number n ? n.doubleValue() : value == null ? fallback : Double.parseDouble(String.valueOf(value));
   }

   private static String safeName(String value) {
      return value.replaceAll("[^a-zA-Z0-9._-]", "_");
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
}
