package dev.xirreal.viewfinder.capture;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.Window;
import dev.xirreal.viewfinder.Viewfinder;
import dev.xirreal.viewfinder.ViewfinderClient;
import dev.xirreal.viewfinder.compat.MinecraftCompat;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Optional;
import java.util.stream.Stream;
import net.fabricmc.loader.api.FabricLoader;
import net.irisshaders.iris.Iris;
import net.minecraft.client.Minecraft;

public class DiagnosticsSnapshot {

   public static JsonObject create() {
      return create(false);
   }

   public static JsonObject create(boolean includeSamples) {
      Minecraft minecraft = Minecraft.getInstance();
      JsonObject snapshot = new JsonObject();
      snapshot.addProperty("schemaVersion", 3);
      snapshot.addProperty("generatedAt", System.currentTimeMillis());
      snapshot.add("environment", environment(minecraft));
      snapshot.add("shaderpack", shaderpack(minecraft));
      snapshot.add("render", renderState(minecraft));
      snapshot.add("errors", Viewfinder.getErrorCapture().toJson());
      snapshot.add("metrics", Viewfinder.getMetricsCollector().toJson(includeSamples));
      snapshot.addProperty("metricsIncludeSamples", includeSamples);
      snapshot.add("pipeline", CurrentPass.pipelineJson());
      snapshot.add("programs", ProgramRegistry.listPrograms());
      snapshot.add("textures", TextureResolver.listTextures());
      snapshot.add("ssbos", SSBOResolver.listBuffers());
      snapshot.add("patchedShaders", patchedShaders(minecraft));
      snapshot.add("screenshot", screenshot());
      snapshot.add("agentActions", agentActions(snapshot));
      return snapshot;
   }

   private static JsonObject environment(Minecraft minecraft) {
      JsonObject environment = new JsonObject();
      environment.addProperty("viewfinderVersion", Viewfinder.VERSION);
      environment.addProperty("minecraftVersion", minecraft.getLaunchedVersion());
      environment.addProperty("minecraftVersionType", MinecraftCompat.versionType());
      environment.addProperty("gameDirectory", minecraft.gameDirectory.getAbsolutePath());
      environment.add("mods", mods());
      return environment;
   }

   private static JsonObject mods() {
      JsonObject mods = new JsonObject();
      for (String id : new String[] { "fabricloader", "fabric-api", "iris", "sodium" }) {
         mods.addProperty(id, modVersion(id));
      }
      return mods;
   }

   private static String modVersion(String id) {
      Optional<String> version = FabricLoader.getInstance()
         .getModContainer(id)
         .map(container -> container.getMetadata().getVersion().getFriendlyString());
      return version.orElse("not loaded");
   }

   private static JsonObject shaderpack(Minecraft minecraft) {
      JsonObject shaderpack = new JsonObject();
      try {
         String packName = Iris.getCurrentPackName();
         shaderpack.addProperty("name", packName);
         Path shaderpacksDir = minecraft.gameDirectory.toPath().resolve("shaderpacks");
         Path packPath = shaderpacksDir.resolve(packName);
         if (Files.exists(packPath)) {
            shaderpack.addProperty("path", packPath.toString());
         }
      } catch (Exception e) {
         shaderpack.addProperty("name", "unknown");
         shaderpack.addProperty("error", message(e));
      }

      try {
         shaderpack.addProperty("debugEnabled", Iris.getIrisConfig().areDebugOptionsEnabled());
      } catch (Exception e) {
         shaderpack.addProperty("debugError", message(e));
      }

      try {
         JsonArray includeFailures = new JsonArray();
         Iris.getCurrentPack().ifPresent(pack -> pack.getShaderPackOptions().getIncludes().getFailures().forEach((path, failure) -> {
            JsonObject entry = new JsonObject();
            entry.addProperty("path", path.toString());
            entry.addProperty("error", failure.toString());
            includeFailures.add(entry);
         }));
         shaderpack.add("includeFailures", includeFailures);
      } catch (Exception e) {
         shaderpack.addProperty("includeDiagnosticsError", message(e));
      }

      try {
         Object pipeline = Iris.getPipelineManager().getPipelineNullable();
         if (pipeline == null) {
            shaderpack.add("pipelineClass", JsonNull.INSTANCE);
         } else {
            shaderpack.addProperty("pipelineClass", pipeline.getClass().getName());
            shaderpack.addProperty("pipelineSimpleName", pipeline.getClass().getSimpleName());
         }
      } catch (Exception e) {
         shaderpack.addProperty("pipelineError", message(e));
      }

      String currentPass = CurrentPass.getCurrentPass();
      if (currentPass != null) {
         shaderpack.addProperty("currentPass", currentPass);
      }
      return shaderpack;
   }

   private static JsonObject renderState(Minecraft minecraft) {
      JsonObject render = new JsonObject();
      try {
         render.add("settings", renderSettings(minecraft));
      } catch (Exception e) {
         render.addProperty("settingsError", message(e));
      }

      try {
         Window window = minecraft.getWindow();
         JsonObject windowJson = new JsonObject();
         windowJson.addProperty("width", window.getWidth());
         windowJson.addProperty("height", window.getHeight());
         windowJson.addProperty("guiScaledWidth", window.getGuiScaledWidth());
         windowJson.addProperty("guiScaledHeight", window.getGuiScaledHeight());
         windowJson.addProperty("fullscreen", MinecraftCompat.isFullscreen(minecraft));
         windowJson.addProperty("focused", window.isFocused());
         render.add("window", windowJson);
      } catch (Exception e) {
         render.addProperty("windowError", message(e));
      }

      try {
         RenderTarget target = MinecraftCompat.mainRenderTarget(minecraft);
         JsonObject renderTarget = new JsonObject();
         renderTarget.addProperty("width", target.width);
         renderTarget.addProperty("height", target.height);
         render.add("mainRenderTarget", renderTarget);
      } catch (Exception e) {
         render.addProperty("mainRenderTargetError", message(e));
      }

      try {
         render.addProperty("fps", minecraft.getFps());
      } catch (Exception e) {
         render.addProperty("fpsError", message(e));
      }

      try {
         render.addProperty("gpuUtilization", minecraft.getGpuUtilization());
      } catch (Exception e) {
         render.addProperty("gpuUtilizationError", message(e));
      }

      try {
         render.add("openGlDriver", ProgramRegistry.driverInfo());
      } catch (Exception e) {
         render.addProperty("openGlDriverError", message(e));
      }

      return render;
   }

   public static JsonObject renderSettings(Minecraft minecraft) {
      JsonObject settings = new JsonObject();
      settings.addProperty("renderDistance", minecraft.options.renderDistance().get());
      settings.addProperty("effectiveRenderDistance", minecraft.options.getEffectiveRenderDistance());
      settings.addProperty("fov", minecraft.options.fov().get());
      return settings;
   }

   private static JsonObject patchedShaders(Minecraft minecraft) {
      JsonObject patched = new JsonObject();
      Path patchedDir = minecraft.gameDirectory.toPath().resolve("patched_shaders");
      patched.addProperty("path", patchedDir.toString());
      patched.addProperty("exists", Files.isDirectory(patchedDir));

      JsonArray files = new JsonArray();
      if (Files.isDirectory(patchedDir)) {
         try (Stream<Path> stream = Files.list(patchedDir)) {
            stream.sorted(Comparator.comparing(path -> path.getFileName().toString())).forEach(path -> files.add(patchedShaderFile(path)));
         } catch (Exception e) {
            patched.addProperty("error", message(e));
         }
      }
      patched.add("files", files);
      patched.addProperty("count", files.size());
      return patched;
   }

   private static JsonObject patchedShaderFile(Path path) {
      JsonObject file = new JsonObject();
      String name = path.getFileName().toString();
      file.addProperty("name", name);
      file.addProperty("path", path.toString());
      file.addProperty("errored", name.toLowerCase().contains("errored"));
      try {
         file.addProperty("sizeBytes", Files.size(path));
         file.addProperty("modifiedAt", Files.getLastModifiedTime(path).toMillis());
      } catch (Exception e) {
         file.addProperty("metadataError", message(e));
      }
      return file;
   }

   private static JsonObject screenshot() {
      JsonObject screenshot = new JsonObject();
      screenshot.addProperty("scheduled", ViewfinderClient.getScreenshotScheduler().isActive());
      String lastScreenshotPath = ViewfinderClient.getScreenshotScheduler().getLastScreenshotPath();
      if (lastScreenshotPath == null) {
         screenshot.add("lastPath", null);
      } else {
         screenshot.addProperty("lastPath", lastScreenshotPath);
      }
      return screenshot;
   }

   private static JsonArray agentActions(JsonObject snapshot) {
      JsonArray actions = new JsonArray();
      int errorCount = snapshot.getAsJsonArray("errors").size();
      if (errorCount > 0) {
         actions.add(action("Resolve included shader errors", null, null, null,
            "Use errors[].compilerMessages in this response directly; read a shader source or patched-shader resource only when source context is needed. Do not call get_diagnostics again for the same errors"));
      }

      JsonObject textures = snapshot.getAsJsonObject("textures");
      if (textures != null && textures.has("colortex") && textures.getAsJsonArray("colortex").size() > 0) {
         actions.add(action("Probe a render target texture", "inspect_texture", "{\"name\":\"colortex0\"}", "/viewfinder texture inspect name colortex0", "Texture probes return bounded stats before dumping"));
      }

      JsonObject ssbos = snapshot.getAsJsonObject("ssbos");
      if (ssbos != null && ssbos.has("buffers") && ssbos.getAsJsonArray("buffers").size() > 0) {
         JsonObject first = ssbos.getAsJsonArray("buffers").get(0).getAsJsonObject();
         int index = first.get("index").getAsInt();
         actions.add(action("Preview an SSBO", "inspect_ssbo", "{\"index\":" + index + "}", "/viewfinder ssbo inspect " + index, "SSBO probes preview bytes, ints, uints, and floats"));
      }

      JsonObject shaderpack = snapshot.getAsJsonObject("shaderpack");
      if (shaderpack != null && shaderpack.has("debugEnabled") && !shaderpack.get("debugEnabled").getAsBoolean()) {
         actions.add(action("Enable Iris debug options", null, null, "/viewfinder patched_shaders", "Patched shader files may be unavailable while Iris debug options are disabled"));
      }

      return actions;
   }

   private static JsonObject action(String label, String tool, String arguments, String command, String reason) {
      JsonObject action = new JsonObject();
      action.addProperty("label", label);
      if (tool != null) {
         action.addProperty("tool", tool);
      }
      if (arguments != null) {
         action.add("arguments", com.google.gson.JsonParser.parseString(arguments));
      }
      if (command != null) {
         action.addProperty("command", command);
      }
      action.addProperty("reason", reason);
      return action;
   }

   private static String message(Exception e) {
      return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
   }
}
