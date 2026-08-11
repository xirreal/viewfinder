package dev.xirreal.viewfinder.commands;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import dev.xirreal.viewfinder.Viewfinder;
import dev.xirreal.viewfinder.ViewfinderClient;
import dev.xirreal.viewfinder.capture.DiagnosticsSnapshot;
import dev.xirreal.viewfinder.capture.ErrorCapture;
import dev.xirreal.viewfinder.capture.SSBODumper;
import dev.xirreal.viewfinder.capture.SSBOInspector;
import dev.xirreal.viewfinder.capture.SSBOResolver;
import dev.xirreal.viewfinder.capture.TextureDumper;
import dev.xirreal.viewfinder.capture.TextureInspector;
import dev.xirreal.viewfinder.capture.TextureResolver;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.irisshaders.iris.Iris;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

public class ShaderDebugCommand {

   private static final int DEFAULT_ERROR_LIMIT = 8;

   public static void register() {
      ClientCommandRegistrationCallback.EVENT.register(ShaderDebugCommand::registerCommands);
   }

   private static int executeTextureDump(CommandContext<FabricClientCommandSource> ctx, String name, int id, boolean raw) {
      FabricClientCommandSource source = ctx.getSource();
      int textureId;
      String baseName;

      if (name != null) {
         textureId = TextureResolver.resolveTexture(name);
         if (textureId == -1) {
            ChatFeedback.error(source, "Unknown texture: " + name);
            ChatFeedback.commandHint(source, "/viewfinder texture list", "List available texture names");
            return 1;
         }
         baseName = name;
      } else {
         textureId = id;
         baseName = "texture_" + textureId;
      }

      String ext = raw ? ".bin" : ".png";
      String outputDir = Minecraft.getInstance().gameDirectory.toPath().resolve("texture_dumps").toString();
      String outputPath = outputDir + File.separator + baseName + ext;

      try {
         JsonObject result = raw ? TextureDumper.dumpTextureRaw(textureId, outputPath) : TextureDumper.dumpTexture(textureId, outputPath);

         if (result.has("error")) {
            ChatFeedback.error(source, "Failed to dump texture: " + jsonString(result, "error", "unknown error"));
         } else {
            printTextureDumpResult(source, baseName, textureId, result, raw);
         }
      } catch (Exception e) {
         ChatFeedback.error(source, "Failed to dump texture: " + ChatFeedback.exceptionMessage(e));
      }

      return 1;
   }

   private static void registerCommands(CommandDispatcher<FabricClientCommandSource> dispatcher, CommandBuildContext registryAccess) {
      dispatcher.register(
         ClientCommands.literal("viewfinder")
            .executes(ctx -> {
               printHelp(ctx.getSource());
               return 1;
            })
            .then(
               ClientCommands.literal("help").executes(ctx -> {
                  printHelp(ctx.getSource());
                  return 1;
               })
            )
            .then(
               ClientCommands.literal("status").executes(ctx -> {
                  printStatus(ctx.getSource());
                  return 1;
               })
            )
            .then(
               ClientCommands.literal("reload").executes(ctx -> {
                  executeReload(ctx.getSource());
                  return 1;
               })
            )
            .then(
               ClientCommands.literal("errors")
                  .executes(ctx -> {
                     printErrors(ctx.getSource(), Viewfinder.getErrorCapture().getErrors(), DEFAULT_ERROR_LIMIT, "Captured Errors");
                     return 1;
                  })
                  .then(
                     ClientCommands.literal("all").executes(ctx -> {
                        printErrors(ctx.getSource(), Viewfinder.getErrorCapture().getErrors(), Integer.MAX_VALUE, "Captured Errors");
                        return 1;
                     })
                  )
                  .then(
                     ClientCommands.literal("clear").executes(ctx -> {
                        int count = Viewfinder.getErrorCapture().getErrors().size();
                        Viewfinder.getErrorCapture().clearErrors();
                        ChatFeedback.success(ctx.getSource(), "Cleared " + count + " captured error(s)");
                        return 1;
                     })
                  )
            )
            .then(
               ClientCommands.literal("snapshot").executes(ctx -> {
                  printSnapshot(ctx.getSource());
                  return 1;
               })
            )
            .then(
               ClientCommands.literal("screenshot")
                  .executes(ctx -> scheduleScreenshot(ctx.getSource(), 1))
                  .then(
                     ClientCommands.literal("result").executes(ctx -> {
                        String path = ViewfinderClient.getScreenshotScheduler().getLastScreenshotPath();
                        if (path != null) {
                           ChatFeedback.pathRow(ctx.getSource(), "Last screenshot", path);
                        } else {
                           ChatFeedback.warning(ctx.getSource(), "No screenshot taken yet");
                        }
                        return 1;
                     })
                  )
                  .then(ClientCommands.argument("frames", IntegerArgumentType.integer(1)).executes(ctx -> scheduleScreenshot(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "frames"))))
            )
            .then(
               ClientCommands.literal("metrics")
                  .executes(ctx -> {
                     printMetrics(ctx.getSource());
                     return 1;
                  })
                  .then(
                     ClientCommands.literal("reset").executes(ctx -> {
                        Viewfinder.getMetricsCollector().reset();
                        ChatFeedback.success(ctx.getSource(), "GPU timings reset");
                        return 1;
                     })
                  )
                  .then(
                     ClientCommands.literal("capture")
                        .executes(ctx -> captureMetrics(ctx.getSource(), 60))
                        .then(ClientCommands.argument("frames", IntegerArgumentType.integer(1, 600))
                           .executes(ctx -> captureMetrics(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "frames"))))
                  )
            )
            .then(
               ClientCommands.literal("ssbo")
                  .then(
                     ClientCommands.literal("dump").then(
                        ClientCommands.argument("index", IntegerArgumentType.integer(0)).executes(ctx -> {
                           executeSsboDump(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "index"));
                           return 1;
                        })
                     )
                  )
                  .then(
                     ClientCommands.literal("list").executes(ctx -> {
                        printSsboList(ctx.getSource());
                        return 1;
                     })
                  )
                  .then(
                     ClientCommands.literal("inspect").then(
                        ClientCommands.argument("index", IntegerArgumentType.integer(0))
                           .executes(ctx -> {
                              printSsboInspection(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "index"), SSBOInspector.defaultPreviewBytes());
                              return 1;
                           })
                           .then(
                              ClientCommands.argument("bytes", IntegerArgumentType.integer(1)).executes(ctx -> {
                                 printSsboInspection(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "index"), IntegerArgumentType.getInteger(ctx, "bytes"));
                                 return 1;
                              })
                           )
                     )
                  )
            )
            .then(
               ClientCommands.literal("texture")
                  .then(
                     ClientCommands.literal("dump")
                        .then(
                           ClientCommands.literal("name").then(
                              ClientCommands.argument("name", StringArgumentType.word())
                                 .executes(ctx -> executeTextureDump(ctx, StringArgumentType.getString(ctx, "name"), 0, false))
                                 .then(
                                    ClientCommands.argument("raw", BoolArgumentType.bool()).executes(ctx ->
                                       executeTextureDump(ctx, StringArgumentType.getString(ctx, "name"), 0, BoolArgumentType.getBool(ctx, "raw"))
                                    )
                                 )
                           )
                        )
                        .then(
                           ClientCommands.literal("id").then(
                              ClientCommands.argument("id", IntegerArgumentType.integer())
                                 .executes(ctx -> executeTextureDump(ctx, null, IntegerArgumentType.getInteger(ctx, "id"), false))
                                 .then(
                                    ClientCommands.argument("raw", BoolArgumentType.bool()).executes(ctx ->
                                       executeTextureDump(ctx, null, IntegerArgumentType.getInteger(ctx, "id"), BoolArgumentType.getBool(ctx, "raw"))
                                    )
                                 )
                           )
                        )
                  )
                  .then(
                     ClientCommands.literal("list").executes(ctx -> {
                        printTextureList(ctx.getSource());
                        return 1;
                     })
                  )
                  .then(
                     ClientCommands.literal("inspect")
                        .then(
                           ClientCommands.literal("name").then(
                              ClientCommands.argument("name", StringArgumentType.word())
                                 .executes(ctx -> {
                                    printTextureInspection(
                                       ctx.getSource(),
                                       TextureInspector.inspectByName(StringArgumentType.getString(ctx, "name"), TextureInspector.defaultSampleCount())
                                    );
                                    return 1;
                                 })
                                 .then(
                                    ClientCommands.argument("samples", IntegerArgumentType.integer(1)).executes(ctx -> {
                                       printTextureInspection(
                                          ctx.getSource(),
                                          TextureInspector.inspectByName(
                                             StringArgumentType.getString(ctx, "name"),
                                             IntegerArgumentType.getInteger(ctx, "samples")
                                          )
                                       );
                                       return 1;
                                    })
                                 )
                           )
                        )
                        .then(
                           ClientCommands.literal("id").then(
                              ClientCommands.argument("id", IntegerArgumentType.integer())
                                 .executes(ctx -> {
                                    printTextureInspection(ctx.getSource(), TextureInspector.inspectTexture(IntegerArgumentType.getInteger(ctx, "id"), TextureInspector.defaultSampleCount()));
                                    return 1;
                                 })
                                 .then(
                                    ClientCommands.argument("samples", IntegerArgumentType.integer(1)).executes(ctx -> {
                                       printTextureInspection(ctx.getSource(), TextureInspector.inspectTexture(IntegerArgumentType.getInteger(ctx, "id"), IntegerArgumentType.getInteger(ctx, "samples")));
                                       return 1;
                                    })
                                 )
                           )
                        )
                  )
                  .then(
                     ClientCommands.literal("sample")
                        .then(
                           ClientCommands.literal("name").then(
                              ClientCommands.argument("name", StringArgumentType.word()).then(
                                 ClientCommands.argument("x", IntegerArgumentType.integer(0)).then(
                                    ClientCommands.argument("y", IntegerArgumentType.integer(0))
                                       .executes(ctx -> {
                                          printTextureInspection(
                                             ctx.getSource(),
                                             TextureInspector.inspectByName(
                                                StringArgumentType.getString(ctx, "name"),
                                                1,
                                                IntegerArgumentType.getInteger(ctx, "x"),
                                                IntegerArgumentType.getInteger(ctx, "y"),
                                                0
                                             )
                                          );
                                          return 1;
                                       })
                                       .then(
                                          ClientCommands.argument("z", IntegerArgumentType.integer(0)).executes(ctx -> {
                                             printTextureInspection(
                                                ctx.getSource(),
                                                TextureInspector.inspectByName(
                                                   StringArgumentType.getString(ctx, "name"),
                                                   1,
                                                   IntegerArgumentType.getInteger(ctx, "x"),
                                                   IntegerArgumentType.getInteger(ctx, "y"),
                                                   IntegerArgumentType.getInteger(ctx, "z")
                                                )
                                             );
                                             return 1;
                                          })
                                       )
                                 )
                              )
                           )
                        )
                        .then(
                           ClientCommands.literal("id").then(
                              ClientCommands.argument("id", IntegerArgumentType.integer()).then(
                                 ClientCommands.argument("x", IntegerArgumentType.integer(0)).then(
                                    ClientCommands.argument("y", IntegerArgumentType.integer(0))
                                       .executes(ctx -> {
                                          printTextureInspection(
                                             ctx.getSource(),
                                             TextureInspector.inspectTexture(
                                                IntegerArgumentType.getInteger(ctx, "id"),
                                                1,
                                                IntegerArgumentType.getInteger(ctx, "x"),
                                                IntegerArgumentType.getInteger(ctx, "y"),
                                                0
                                             )
                                          );
                                          return 1;
                                       })
                                       .then(
                                          ClientCommands.argument("z", IntegerArgumentType.integer(0)).executes(ctx -> {
                                             printTextureInspection(
                                                ctx.getSource(),
                                                TextureInspector.inspectTexture(
                                                   IntegerArgumentType.getInteger(ctx, "id"),
                                                   1,
                                                   IntegerArgumentType.getInteger(ctx, "x"),
                                                   IntegerArgumentType.getInteger(ctx, "y"),
                                                   IntegerArgumentType.getInteger(ctx, "z")
                                                )
                                             );
                                             return 1;
                                          })
                                       )
                                 )
                              )
                           )
                        )
                  )
            )
            .then(
               ClientCommands.literal("patched_shaders").executes(ctx -> {
                  printPatchedShaders(ctx.getSource());
                  return 1;
               })
            )
      );
   }

   private static void printHelp(FabricClientCommandSource source) {
      ChatFeedback.header(source, "commands");
      ChatFeedback.commandHint(source, "/viewfinder status", "shader and API status");
      ChatFeedback.commandHint(source, "/viewfinder reload", "reload shaders");
      ChatFeedback.commandHint(source, "/viewfinder errors", "recent shader errors");
      ChatFeedback.commandHint(source, "/viewfinder metrics", "GPU pass timings");
      ChatFeedback.commandHint(source, "/viewfinder screenshot", "capture the next frame");
      ChatFeedback.commandHint(source, "/viewfinder texture list", "textures");
      ChatFeedback.commandHint(source, "/viewfinder ssbo list", "shader buffers");
      ChatFeedback.commandHint(source, "/viewfinder snapshot", "full diagnostics");
   }

   private static void printSnapshot(FabricClientCommandSource source) {
      try {
         JsonObject snapshot = DiagnosticsSnapshot.create();
         JsonObject environment = snapshot.getAsJsonObject("environment");
         JsonObject shaderpack = snapshot.getAsJsonObject("shaderpack");
         JsonObject textures = snapshot.getAsJsonObject("textures");
         JsonObject ssbos = snapshot.getAsJsonObject("ssbos");

         ChatFeedback.header(source, "Agent Snapshot");
         ChatFeedback.row(source, "MCP", "get_diagnostics");
         ChatFeedback.row(source, "Version", jsonString(environment, "viewfinderVersion", "unknown"));
         ChatFeedback.row(source, "Minecraft", jsonString(environment, "minecraftVersion", "unknown"));
         ChatFeedback.row(source, "Shaderpack", jsonString(shaderpack, "name", "unknown"));
         if (shaderpack.has("pipelineSimpleName")) {
            ChatFeedback.row(source, "Pipeline", jsonString(shaderpack, "pipelineSimpleName", "unknown"));
         }
         if (shaderpack.has("debugEnabled")) {
            boolean debug = shaderpack.get("debugEnabled").getAsBoolean();
            ChatFeedback.row(source, "Debug mode", Component.literal(debug ? "enabled" : "disabled").withStyle(debug ? ChatFormatting.GREEN : ChatFormatting.RED));
         }

         ChatFeedback.row(source, "Errors", String.valueOf(snapshot.getAsJsonArray("errors").size()));
         ChatFeedback.row(source, "Textures", jsonArraySize(textures, "colortex") + " colortex, " + jsonArraySize(textures, "custom") + " custom");
         ChatFeedback.row(source, "SSBOs", String.valueOf(jsonArraySize(ssbos, "buffers")));
         JsonObject patched = snapshot.getAsJsonObject("patchedShaders");
         ChatFeedback.row(source, "Patched shaders", jsonInt(patched, "count", 0) + " file(s)");

         JsonArray actions = snapshot.getAsJsonArray("agentActions");
         if (actions != null && actions.size() > 0) {
            ChatFeedback.row(source, "Next actions", String.valueOf(actions.size()));
            for (int i = 0; i < Math.min(3, actions.size()); i++) {
               JsonObject action = actions.get(i).getAsJsonObject();
               if (action.has("command")) {
                  ChatFeedback.commandHint(source, action.get("command").getAsString(), jsonString(action, "reason", "Suggested by snapshot"));
               }
            }
         }
      } catch (Exception e) {
         ChatFeedback.error(source, "Failed to create snapshot: " + ChatFeedback.exceptionMessage(e));
      }
   }

   private static void printStatus(FabricClientCommandSource source) {
      ChatFeedback.header(source, "Status");
      ChatFeedback.row(source, "Version", Viewfinder.VERSION);

      try {
         ChatFeedback.row(source, "Shaderpack", Iris.getCurrentPackName());
      } catch (Exception e) {
         ChatFeedback.row(source, "Shaderpack", Component.literal("unknown").withStyle(ChatFormatting.RED));
      }

      try {
         boolean debug = Iris.getIrisConfig().areDebugOptionsEnabled();
         ChatFeedback.row(source, "Debug mode", Component.literal(debug ? "enabled" : "disabled").withStyle(debug ? ChatFormatting.GREEN : ChatFormatting.RED));
      } catch (Exception e) {
         ChatFeedback.row(source, "Debug mode", Component.literal("unknown").withStyle(ChatFormatting.RED));
      }

      int errorCount = Viewfinder.getErrorCapture().getErrors().size();
      MutableComponent errors = Component.literal(String.valueOf(errorCount)).withStyle(errorCount == 0 ? ChatFormatting.GREEN : ChatFormatting.RED);
      if (errorCount > 0) {
         errors.append(" ").append(ChatFeedback.suggestButton("view", "/viewfinder errors"));
      }
      ChatFeedback.row(source, "Errors", errors);

      ChatFeedback.row(source, "MCP", "http://127.0.0.1:7150/mcp");

      boolean screenshotActive = ViewfinderClient.getScreenshotScheduler().isActive();
      ChatFeedback.row(source, "Screenshot", Component.literal(screenshotActive ? "scheduled" : "idle").withStyle(screenshotActive ? ChatFormatting.YELLOW : ChatFormatting.GREEN));
   }

   private static void executeReload(FabricClientCommandSource source) {
      Viewfinder.getErrorCapture().clearErrors();
      ChatFeedback.info(source, "Reloading shaders");

      try {
         Iris.reload();
         List<ErrorCapture.ErrorEntry> errors = Viewfinder.getErrorCapture().getErrors();
         if (errors.isEmpty()) {
            ChatFeedback.success(source, "Shaders reloaded successfully");
         } else {
            ChatFeedback.warning(source, "Shaders reloaded with " + errors.size() + " fresh error(s)");
            printErrors(source, errors, DEFAULT_ERROR_LIMIT, "Reload Errors");
         }
      } catch (Exception e) {
         ChatFeedback.error(source, "Shader reload failed: " + ChatFeedback.exceptionMessage(e));
         List<ErrorCapture.ErrorEntry> errors = Viewfinder.getErrorCapture().getErrors();
         if (!errors.isEmpty()) {
            printErrors(source, errors, DEFAULT_ERROR_LIMIT, "Reload Errors");
         }
      }
   }

   private static int scheduleScreenshot(FabricClientCommandSource source, int frames) {
      ViewfinderClient.getScreenshotScheduler().scheduleScreenshot(frames, ShaderDebugCommand::sendScreenshotSaved);
      String when = frames == 1 ? "next frame" : "in " + frames + " frames";
      ChatFeedback.success(source, "Screenshot scheduled for " + when);
      return 1;
   }

   private static int captureMetrics(FabricClientCommandSource source, int frames) {
      try {
         Viewfinder.getMetricsCollector().captureFrames(frames).thenAccept(result -> {
            ChatFeedback.success(source, "Captured GPU timings for " + frames + " frame(s)");
            printMetrics(source);
         });
         ChatFeedback.info(source, "Profiling the next " + frames + " frame(s)");
      } catch (Exception e) {
         ChatFeedback.error(source, "Could not start profiling: " + ChatFeedback.exceptionMessage(e));
      }
      return 1;
   }

   private static void sendScreenshotSaved(String path) {
      Minecraft minecraft = Minecraft.getInstance();
      if (minecraft.player != null) {
         minecraft.player.sendSystemMessage(ChatFeedback.savedPath("Screenshot saved", path));
      }
   }

   private static void printErrors(FabricClientCommandSource source, List<ErrorCapture.ErrorEntry> errors, int limit, String title) {
      if (errors.isEmpty()) {
         ChatFeedback.success(source, "No errors captured");
         return;
      }

      List<ErrorCapture.ErrorEntry> sorted = new ArrayList<>(errors);
      sorted.sort(Comparator.comparingLong((ErrorCapture.ErrorEntry error) -> error.timestamp).reversed());

      int shown = Math.min(sorted.size(), limit);
      ChatFeedback.header(source, title);
      ChatFeedback.row(source, "Total", sorted.size() + " error(s), newest first");
      for (int i = 0; i < shown; i++) {
         ChatFeedback.errorEntry(source, i + 1, sorted.get(i));
      }

      if (shown < sorted.size()) {
         ChatFeedback.warning(source, "Showing " + shown + " of " + sorted.size() + " error(s)");
         ChatFeedback.commandHint(source, "/viewfinder errors all", "Print the full captured error buffer");
      }
   }

   private static void printMetrics(FabricClientCommandSource source) {
      JsonObject timings = Viewfinder.getMetricsCollector().toJson();
      if (timings.size() == 0) {
         ChatFeedback.warning(source, "No GPU timings collected yet");
         return;
      }

      List<Map.Entry<String, JsonElement>> entries = new ArrayList<>(timings.entrySet());
      entries.sort((a, b) -> Long.compare(jsonLong(b.getValue().getAsJsonObject(), "avg", 0L), jsonLong(a.getValue().getAsJsonObject(), "avg", 0L)));

      ChatFeedback.header(source, "GPU timings · " + entries.size() + " passes");
      for (Map.Entry<String, JsonElement> entry : entries) {
         JsonObject pass = entry.getValue().getAsJsonObject();
         int samples = pass.has("samples") && pass.get("samples").isJsonArray() ? pass.getAsJsonArray("samples").size() : 0;
         MutableComponent line = Component.literal(entry.getKey()).withStyle(ChatFormatting.AQUA)
            .append(Component.literal("  " + ChatFeedback.formatNanos(jsonLong(pass, "avg", 0L))).withStyle(ChatFormatting.GRAY))
            .append(Component.literal(" avg · " + ChatFeedback.formatNanos(jsonLong(pass, "latest", 0L)) + " last · " + samples + " samples").withStyle(ChatFormatting.DARK_GRAY));
         ChatFeedback.listItem(source, line);
      }
   }

   private static void executeSsboDump(FabricClientCommandSource source, int index) {
      int bufferId = SSBOResolver.resolveBufferId(index);
      if (bufferId == -1) {
         ChatFeedback.error(source, "No SSBO found at index " + index);
         ChatFeedback.commandHint(source, "/viewfinder ssbo list", "List active SSBO indices");
         return;
      }

      try {
         String baseName = "ssbo_" + index;
         String outputDir = Minecraft.getInstance().gameDirectory.toPath().resolve("ssbo_dumps").toString();
         String outputPath = outputDir + File.separator + baseName + ".bin";

         JsonObject result = SSBODumper.dumpBuffer(bufferId, index, outputPath);
         if (result.has("error")) {
            ChatFeedback.error(source, "Failed to dump SSBO: " + jsonString(result, "error", "unknown error"));
            return;
         }

         ChatFeedback.success(source, "SSBO dumped: index " + index);
         List<String> details = new ArrayList<>();
         details.add("GL id " + jsonInt(result, "bufferId", bufferId));
         long totalBytes = jsonLong(result, "totalBytes", -1L);
         if (totalBytes >= 0L) {
            details.add(ChatFeedback.formatBytes(totalBytes));
         }
         ChatFeedback.row(source, "Details", String.join(" | ", details));
         ChatFeedback.pathRow(source, "Output", jsonString(result, "path", outputPath));
      } catch (Exception e) {
         ChatFeedback.error(source, "Failed to dump SSBO: " + ChatFeedback.exceptionMessage(e));
      }
   }

   private static void printSsboList(FabricClientCommandSource source) {
      try {
         JsonObject result = SSBOResolver.listBuffers();
         var buffers = result.getAsJsonArray("buffers");
         if (buffers == null || buffers.size() == 0) {
            ChatFeedback.warning(source, "No active SSBOs found");
            return;
         }

         ChatFeedback.header(source, "Active SSBOs");
         ChatFeedback.row(source, "Count", String.valueOf(buffers.size()));
         for (JsonElement elem : buffers) {
            JsonObject buffer = elem.getAsJsonObject();
            int index = jsonInt(buffer, "index", -1);
            int glId = jsonInt(buffer, "glId", -1);
            long sizeBytes = jsonLong(buffer, "sizeBytes", -1L);
            MutableComponent line = Component.literal("index " + index).withStyle(ChatFormatting.AQUA)
               .append(Component.literal(" GL id " + glId).withStyle(ChatFormatting.WHITE))
               .append(Component.literal(sizeBytes >= 0L ? " " + ChatFeedback.formatBytes(sizeBytes) : "").withStyle(ChatFormatting.GRAY))
               .append(" ")
               .append(ChatFeedback.suggestButton("dump", "/viewfinder ssbo dump " + index))
               .append(" ")
               .append(ChatFeedback.suggestButton("inspect", "/viewfinder ssbo inspect " + index))
               .append(" ")
               .append(ChatFeedback.copyButton("copy id", String.valueOf(glId)));
            ChatFeedback.listItem(source, line);
         }
      } catch (Exception e) {
         ChatFeedback.error(source, "Failed to list SSBOs: " + ChatFeedback.exceptionMessage(e));
      }
   }

   private static void printTextureList(FabricClientCommandSource source) {
      try {
         JsonObject result = TextureResolver.listTextures();
         if (result.has("error")) {
            ChatFeedback.error(source, jsonString(result, "error", "Failed to list textures"));
            return;
         }

         var colortex = result.getAsJsonArray("colortex");
         var custom = result.getAsJsonArray("custom");
         ChatFeedback.header(source, "Textures");
         ChatFeedback.row(source, "Colortex", String.valueOf(colortex.size()));
         for (JsonElement elem : colortex) {
            JsonObject texture = elem.getAsJsonObject();
            String name = jsonString(texture, "name", "unknown");
            int textureId = jsonInt(texture, "textureId", -1);
            String dimensions = jsonInt(texture, "width", 0) + "x" + jsonInt(texture, "height", 0);
            MutableComponent line = Component.literal(name).withStyle(ChatFormatting.AQUA)
               .append(Component.literal(" id " + textureId + " " + dimensions).withStyle(ChatFormatting.WHITE))
               .append(Component.literal(texture.has("formatName") ? " " + jsonString(texture, "formatName", "") : "").withStyle(ChatFormatting.GRAY))
               .append(" ")
               .append(ChatFeedback.suggestButton("dump", "/viewfinder texture dump name " + name))
               .append(" ")
               .append(ChatFeedback.suggestButton("inspect", "/viewfinder texture inspect name " + name))
               .append(" ")
               .append(ChatFeedback.suggestButton("raw", "/viewfinder texture dump name " + name + " true"))
               .append(" ")
               .append(ChatFeedback.copyButton("copy id", String.valueOf(textureId)));
            ChatFeedback.listItem(source, line);
         }

         ChatFeedback.row(source, "Custom", String.valueOf(custom.size()));
         for (JsonElement elem : custom) {
            JsonObject texture = elem.getAsJsonObject();
            String name = jsonString(texture, "name", "unknown");
            int textureId = jsonInt(texture, "textureId", -1);
            MutableComponent line = Component.literal(name).withStyle(ChatFormatting.AQUA)
               .append(Component.literal(" id " + textureId).withStyle(ChatFormatting.WHITE))
               .append(Component.literal(texture.has("width") ? " " + jsonInt(texture, "width", 0) + "x" + jsonInt(texture, "height", 0) : "").withStyle(ChatFormatting.GRAY))
               .append(Component.literal(texture.has("formatName") ? " " + jsonString(texture, "formatName", "") : "").withStyle(ChatFormatting.GRAY))
               .append(" ")
               .append(ChatFeedback.suggestButton("dump", "/viewfinder texture dump name " + name))
               .append(" ")
               .append(ChatFeedback.suggestButton("inspect", "/viewfinder texture inspect name " + name))
               .append(" ")
               .append(ChatFeedback.suggestButton("raw", "/viewfinder texture dump name " + name + " true"))
               .append(" ")
               .append(ChatFeedback.copyButton("copy id", String.valueOf(textureId)));
            ChatFeedback.listItem(source, line);
         }
      } catch (Exception e) {
         ChatFeedback.error(source, "Failed to list textures: " + ChatFeedback.exceptionMessage(e));
      }
   }

   private static void printSsboInspection(FabricClientCommandSource source, int index, int bytes) {
      JsonObject result = SSBOInspector.inspectIndex(index, bytes);
      if (result.has("error")) {
         ChatFeedback.error(source, jsonString(result, "error", "Failed to inspect SSBO"));
         ChatFeedback.commandHint(source, "/viewfinder ssbo list", "List active SSBO indices");
         return;
      }

      ChatFeedback.header(source, "SSBO Probe");
      ChatFeedback.row(source, "Index", String.valueOf(index));
      ChatFeedback.row(source, "GL id", String.valueOf(jsonInt(result, "bufferId", -1)));
      ChatFeedback.row(source, "Size", ChatFeedback.formatBytes(jsonLong(result, "totalBytes", 0L)));
      ChatFeedback.row(source, "Inspected", ChatFeedback.formatBytes(jsonLong(result, "inspectedBytes", 0L)) + (jsonBoolean(result, "truncated", false) ? " (truncated)" : ""));
      ChatFeedback.row(source, "MCP", "inspect_ssbo");

      JsonObject preview = result.getAsJsonObject("preview");
      if (preview != null) {
         ChatFeedback.row(source, "Hex", ChatFeedback.compact(jsonString(preview, "hex", ""), 180));
         if (preview.has("float32")) {
            ChatFeedback.row(source, "float32", ChatFeedback.compact(preview.get("float32").toString(), 180));
         }
         if (preview.has("int32")) {
            ChatFeedback.row(source, "int32", ChatFeedback.compact(preview.get("int32").toString(), 180));
         }
         JsonObject floatStats = preview.getAsJsonObject("float32Stats");
         if (floatStats != null && jsonInt(floatStats, "finiteCount", 0) > 0) {
            ChatFeedback.row(
               source,
               "float stats",
               "min " + formatDouble(jsonDouble(floatStats, "min", 0.0)) + ", max " + formatDouble(jsonDouble(floatStats, "max", 0.0)) + ", avg " + formatDouble(jsonDouble(floatStats, "avg", 0.0))
            );
         }
      }
   }

   private static void printTextureInspection(FabricClientCommandSource source, JsonObject result) {
      if (result.has("error")) {
         ChatFeedback.error(source, jsonString(result, "error", "Failed to inspect texture"));
         ChatFeedback.commandHint(source, "/viewfinder texture list", "List available texture names");
         return;
      }

      String name = jsonString(result, "name", null);
      int textureId = jsonInt(result, "textureId", -1);
      String selector = name != null ? "name=" + name : "id=" + textureId;

      ChatFeedback.header(source, "Texture Probe");
      if (name != null) {
         ChatFeedback.row(source, "Name", name);
      }
      ChatFeedback.row(source, "GL id", String.valueOf(textureId));
      ChatFeedback.row(source, "Target", jsonString(result, "targetName", "unknown"));
      ChatFeedback.row(source, "Size", jsonInt(result, "width", 0) + "x" + jsonInt(result, "height", 0) + (jsonInt(result, "depth", 1) > 1 ? "x" + jsonInt(result, "depth", 1) : ""));
      ChatFeedback.row(source, "Format", jsonString(result, "formatName", "unknown") + ", " + jsonInt(result, "components", 0) + " component(s)");
      ChatFeedback.row(source, "MCP", "inspect_texture");

      if (!jsonBoolean(result, "statsAvailable", false)) {
         ChatFeedback.warning(source, jsonString(result, "statsSkippedReason", "Texture statistics unavailable"));
         return;
      }

      ChatFeedback.row(source, "Sampled", jsonInt(result, "sampledPixels", 0) + " pixel(s)" + (jsonBoolean(result, "truncated", false) ? " (strided)" : ""));
      if (result.has("firstPixel")) {
         ChatFeedback.row(source, "First pixel", ChatFeedback.compact(result.get("firstPixel").toString(), 140));
      }
      if (result.has("centerPixel")) {
         ChatFeedback.row(source, "Center pixel", ChatFeedback.compact(result.get("centerPixel").toString(), 140));
      }
      if (result.has("requestedPixel")) {
         ChatFeedback.row(source, "Requested pixel", ChatFeedback.compact(result.get("requestedPixel").toString(), 180));
      }

      JsonArray channels = result.getAsJsonArray("channels");
      if (channels != null) {
         for (JsonElement element : channels) {
            JsonObject channel = element.getAsJsonObject();
            if (jsonInt(channel, "finiteCount", 0) == 0) {
               ChatFeedback.row(source, "Channel " + jsonInt(channel, "index", -1), "no finite samples");
            } else {
               ChatFeedback.row(
                  source,
                  "Channel " + jsonInt(channel, "index", -1),
                  "min " + formatDouble(jsonDouble(channel, "min", 0.0)) + ", max " + formatDouble(jsonDouble(channel, "max", 0.0)) + ", avg " + formatDouble(jsonDouble(channel, "avg", 0.0))
               );
            }
         }
      }
   }

   private static void printTextureDumpResult(FabricClientCommandSource source, String baseName, int textureId, JsonObject result, boolean raw) {
      ChatFeedback.success(source, "Texture dumped: " + baseName);

      List<String> details = new ArrayList<>();
      details.add("id " + textureId);
      int width = jsonInt(result, "width", -1);
      int height = jsonInt(result, "height", -1);
      if (width > 0 && height > 0) {
         details.add(width + "x" + height);
      }
      int depth = jsonInt(result, "depth", 1);
      if (depth > 1) {
         details.add(depth + " layers");
      }
      String format = jsonString(result, "formatName", null);
      if (format != null) {
         details.add(format);
      }
      if (raw) {
         long totalBytes = jsonLong(result, "totalBytes", -1L);
         if (totalBytes >= 0L) {
            details.add(ChatFeedback.formatBytes(totalBytes));
         }
         String pixelType = jsonString(result, "pixelType", null);
         if (pixelType != null) {
            details.add(pixelType);
         }
      }

      ChatFeedback.row(source, "Details", String.join(" | ", details));
      ChatFeedback.pathRow(source, "Output", jsonString(result, "path", "<unknown>"));
   }

   private static void printPatchedShaders(FabricClientCommandSource source) {
      try {
         boolean debug = Iris.getIrisConfig().areDebugOptionsEnabled();
         Path patchedDir = Minecraft.getInstance().gameDirectory.toPath().resolve("patched_shaders");

         ChatFeedback.header(source, "Patched Shaders");
         ChatFeedback.row(source, "Debug mode", Component.literal(debug ? "enabled" : "disabled").withStyle(debug ? ChatFormatting.GREEN : ChatFormatting.RED));
         ChatFeedback.pathRow(source, "Path", patchedDir.toString());
         if (!debug) {
            ChatFeedback.warning(source, "Iris debug options are disabled; patched shader files may not be generated");
         }

         if (!Files.exists(patchedDir) || !Files.isDirectory(patchedDir)) {
            ChatFeedback.warning(source, "No patched shaders directory found");
            return;
         }

         try (Stream<Path> stream = Files.list(patchedDir)) {
            List<Path> files = stream.sorted(Comparator.comparing(path -> path.getFileName().toString())).toList();
            long erroredCount = files.stream().filter(ShaderDebugCommand::isErroredShaderFile).count();
            ChatFeedback.row(source, "Files", files.size() + (erroredCount > 0L ? " (" + erroredCount + " errored)" : ""));

            for (Path path : files) {
               boolean errored = isErroredShaderFile(path);
               MutableComponent line = Component.literal(path.getFileName().toString()).withStyle(errored ? ChatFormatting.RED : ChatFormatting.WHITE)
                  .append(" ")
                  .append(ChatFeedback.copyButton("copy path", path.toString()));
               ChatFeedback.listItem(source, line);
            }
         }
      } catch (Exception e) {
         ChatFeedback.error(source, "Failed to query patched shaders: " + ChatFeedback.exceptionMessage(e));
      }
   }

   private static boolean isErroredShaderFile(Path path) {
      return path.getFileName().toString().toLowerCase().contains("errored");
   }

   private static String jsonString(JsonObject object, String key, String fallback) {
      if (!object.has(key) || object.get(key).isJsonNull()) {
         return fallback;
      }
      return object.get(key).getAsString();
   }

   private static int jsonInt(JsonObject object, String key, int fallback) {
      if (!object.has(key) || object.get(key).isJsonNull()) {
         return fallback;
      }
      return object.get(key).getAsInt();
   }

   private static long jsonLong(JsonObject object, String key, long fallback) {
      if (!object.has(key) || object.get(key).isJsonNull()) {
         return fallback;
      }
      return object.get(key).getAsLong();
   }

   private static boolean jsonBoolean(JsonObject object, String key, boolean fallback) {
      if (!object.has(key) || object.get(key).isJsonNull()) {
         return fallback;
      }
      return object.get(key).getAsBoolean();
   }

   private static double jsonDouble(JsonObject object, String key, double fallback) {
      if (!object.has(key) || object.get(key).isJsonNull()) {
         return fallback;
      }
      return object.get(key).getAsDouble();
   }

   private static int jsonArraySize(JsonObject object, String key) {
      if (object == null || !object.has(key) || !object.get(key).isJsonArray()) {
         return 0;
      }
      return object.getAsJsonArray(key).size();
   }

   private static String formatDouble(double value) {
      if (Math.abs(value) >= 1000.0 || (value != 0.0 && Math.abs(value) < 0.001)) {
         return String.format(Locale.ROOT, "%.4e", value);
      }
      return String.format(Locale.ROOT, "%.4f", value);
   }
}
