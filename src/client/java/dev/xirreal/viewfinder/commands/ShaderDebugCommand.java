package dev.xirreal.viewfinder.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.*;
import dev.xirreal.viewfinder.Viewfinder;
import dev.xirreal.viewfinder.capture.ErrorCapture;
import dev.xirreal.viewfinder.capture.SSBODumper;
import dev.xirreal.viewfinder.capture.SSBOResolver;
import dev.xirreal.viewfinder.capture.TextureDumper;
import dev.xirreal.viewfinder.capture.TextureResolver;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.irisshaders.iris.Iris;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.network.chat.Component;

public class ShaderDebugCommand {

   public static void register() {
      ClientCommandRegistrationCallback.EVENT.register(ShaderDebugCommand::registerCommands);
   }

   private static int executeTextureDump(CommandContext<FabricClientCommandSource> ctx, String name, int id, boolean raw) {
      int textureId;
      String baseName;

      if (name != null) {
         textureId = TextureResolver.resolveTexture(name);
         if (textureId == -1) {
            ctx.getSource().sendFeedback(Component.literal("§cUnknown texture: " + name));
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
         var result = raw ? TextureDumper.dumpTextureRaw(textureId, outputPath) : TextureDumper.dumpTexture(textureId, outputPath);

         if (result.has("error")) {
            ctx.getSource().sendFeedback(Component.literal("§cFailed: " + result.get("error").getAsString()));
         } else {
            ctx.getSource().sendFeedback(Component.literal("§aTexture " + baseName + " dumped to " + result.get("path").getAsString()));
         }
      } catch (Exception e) {
         ctx.getSource().sendFeedback(Component.literal("§cFailed to dump texture: " + e.getMessage()));
      }

      return 1;
   }

   private static void registerCommands(CommandDispatcher<FabricClientCommandSource> dispatcher, CommandBuildContext registryAccess) {
      dispatcher.register(
         ClientCommands.literal("viewfinder")
            // /viewfinder status
            .then(
               ClientCommands.literal("status").executes(ctx -> {
                  ctx.getSource().sendFeedback(Component.literal("§6Viewfinder Status:"));
                  try {
                     String packName = Iris.getCurrentPackName();
                     ctx.getSource().sendFeedback(Component.literal("§f  Shaderpack: §a" + packName));
                  } catch (Exception e) {
                     ctx.getSource().sendFeedback(Component.literal("§f  Shaderpack: §cunknown"));
                  }
                  ctx.getSource().sendFeedback(Component.literal("§f  Errors: §a" + Viewfinder.getErrorCapture().getErrors().size()));
                  ctx.getSource().sendFeedback(Component.literal("§f  HTTP API: §alocalhost:7150"));
                  return 1;
               })
            )
            // /viewfinder reload
            .then(
               ClientCommands.literal("reload").executes(ctx -> {
                  try {
                     Iris.reload();
                     var errors = Viewfinder.getErrorCapture().getErrors();
                     if (errors.isEmpty()) {
                        ctx.getSource().sendFeedback(Component.literal("§aShaders reloaded successfully"));
                     } else {
                        ctx.getSource().sendFeedback(Component.literal("§eShaders reloaded with " + errors.size() + " error(s):"));
                        for (ErrorCapture.ErrorEntry error : errors) {
                           ctx.getSource().sendFeedback(Component.literal("§c[" + error.type + "] " + error.message));
                        }
                     }
                  } catch (Exception e) {
                     ctx.getSource().sendFeedback(Component.literal("§cShader reload failed: " + e.getMessage()));
                     var errors = Viewfinder.getErrorCapture().getErrors();
                     if (!errors.isEmpty()) {
                        for (ErrorCapture.ErrorEntry error : errors) {
                           ctx.getSource().sendFeedback(Component.literal("§c[" + error.type + "] " + error.message));
                        }
                     }
                  }
                  return 1;
               })
            )
            // /viewfinder errors
            .then(
               ClientCommands.literal("errors").executes(ctx -> {
                  var errors = Viewfinder.getErrorCapture().getErrors();
                  if (errors.isEmpty()) {
                     ctx.getSource().sendFeedback(Component.literal("§aNo errors captured"));
                  } else {
                     ctx.getSource().sendFeedback(Component.literal("§e" + errors.size() + " error(s) captured:"));
                     for (ErrorCapture.ErrorEntry error : errors) {
                        ctx.getSource().sendFeedback(Component.literal("§c[" + error.type + "] " + error.message));
                     }
                  }
                  return 1;
               })
            )
            // /viewfinder screenshot [frames]
            // /viewfinder screenshot result
            .then(
               ClientCommands.literal("screenshot")
                  .executes(ctx -> {
                     dev.xirreal.viewfinder.ViewfinderClient.getScreenshotScheduler().scheduleScreenshot(1, path -> {
                        Minecraft.getInstance().player.sendSystemMessage(Component.literal("§aScreenshot saved: " + path));
                     });
                     ctx.getSource().sendFeedback(Component.literal("§aScreenshot scheduled for next frame"));
                     return 1;
                  })
                  .then(
                     ClientCommands.literal("result").executes(ctx -> {
                        String path = dev.xirreal.viewfinder.ViewfinderClient.getScreenshotScheduler().getLastScreenshotPath();
                        if (path != null) {
                           ctx.getSource().sendFeedback(Component.literal("§aLast screenshot: " + path));
                        } else {
                           ctx.getSource().sendFeedback(Component.literal("§eNo screenshot taken yet"));
                        }
                        return 1;
                     })
                  )
                  .then(
                     ClientCommands.argument("frames", IntegerArgumentType.integer(1)).executes(ctx -> {
                        int frames = IntegerArgumentType.getInteger(ctx, "frames");
                        dev.xirreal.viewfinder.ViewfinderClient.getScreenshotScheduler().scheduleScreenshot(frames, path -> {
                           Minecraft.getInstance().player.sendSystemMessage(Component.literal("§aScreenshot saved: " + path));
                        });
                        ctx.getSource().sendFeedback(Component.literal("§aScreenshot scheduled in " + frames + " frames"));
                        return 1;
                     })
                  )
            )
            // /viewfinder metrics
            .then(
               ClientCommands.literal("metrics").executes(ctx -> {
                  var timings = Viewfinder.getMetricsCollector().getTimings();
                  if (timings.isEmpty()) {
                     ctx.getSource().sendFeedback(Component.literal("§eNo GPU timings collected yet"));
                  } else {
                     ctx.getSource().sendFeedback(Component.literal("§6GPU Timings:"));
                     timings.forEach((name, ns) -> {
                        double ms = ns / 1_000_000.0;
                        double us = ns / 1_000.0;

                        String timeStr = ms < 5.0 ? String.format("%.2fµs", us) : String.format("%.2fms", ms);
                        ctx.getSource().sendFeedback(Component.literal("§f  " + name + ": §a" + timeStr));
                     });
                  }
                  return 1;
               })
            )
            // /viewfinder ssbo dump <index>
            // /viewfinder ssbo list
            .then(
               ClientCommands.literal("ssbo")
                  .then(
                     ClientCommands.literal("dump").then(
                        ClientCommands.argument("index", IntegerArgumentType.integer(0)).executes(ctx -> {
                           int index = IntegerArgumentType.getInteger(ctx, "index");
                           int bufferId = SSBOResolver.resolveBufferId(index);
                           if (bufferId == -1) {
                              ctx.getSource().sendFeedback(Component.literal("§cNo SSBO found at index " + index));
                              return 1;
                           }
                           try {
                              String baseName = "ssbo_" + index;
                              String outputDir = Minecraft.getInstance().gameDirectory.toPath().resolve("ssbo_dumps").toString();
                              String outputPath = outputDir + "/" + baseName + ".bin";

                              var result = SSBODumper.dumpBuffer(bufferId, index, outputPath);
                              String str = result.toString();
                              ctx.getSource().sendFeedback(Component.literal("§aSSBO " + index + ": " + str));
                           } catch (Exception e) {
                              ctx.getSource().sendFeedback(Component.literal("§cFailed to dump SSBO: " + e.getMessage()));
                           }
                           return 1;
                        })
                     )
                  )
                  .then(
                     ClientCommands.literal("list").executes(ctx -> {
                        try {
                           var result = SSBOResolver.listBuffers();
                           var buffers = result.getAsJsonArray("buffers");
                           ctx.getSource().sendFeedback(Component.literal("§6Active SSBOs: §f" + buffers.size()));
                           for (var elem : buffers) {
                              var buf = elem.getAsJsonObject();
                              ctx
                                 .getSource()
                                 .sendFeedback(Component.literal("§f  Index " + buf.get("index").getAsInt() + ": GL Buffer ID " + buf.get("glId").getAsInt()));
                           }
                        } catch (Exception e) {
                           ctx.getSource().sendFeedback(Component.literal("§cFailed to list SSBOs: " + e.getMessage()));
                        }
                        return 1;
                     })
                  )
            )
            // /viewfinder texture dump name <name> [raw]
            // /viewfinder texture dump id <id> [raw]
            // /viewfinder texture list
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
                        try {
                           var result = TextureResolver.listTextures();
                           if (result.has("error")) {
                              ctx.getSource().sendFeedback(Component.literal("§c" + result.get("error").getAsString()));
                              return 1;
                           }
                           var colortex = result.getAsJsonArray("colortex");
                           ctx.getSource().sendFeedback(Component.literal("§6Colortex buffers: §f" + colortex.size()));
                           for (var elem : colortex) {
                              var tex = elem.getAsJsonObject();
                              ctx
                                 .getSource()
                                 .sendFeedback(
                                    Component.literal(
                                       "§f  " +
                                          tex.get("name").getAsString() +
                                          " §7(id=" +
                                          tex.get("textureId").getAsInt() +
                                          ", " +
                                          tex.get("width").getAsInt() +
                                          "x" +
                                          tex.get("height").getAsInt() +
                                          ")"
                                    )
                                 );
                           }
                           var custom = result.getAsJsonArray("custom");
                           if (custom.size() > 0) {
                              ctx.getSource().sendFeedback(Component.literal("§6Custom textures: §f" + custom.size()));
                              for (var elem : custom) {
                                 var tex = elem.getAsJsonObject();
                                 ctx
                                    .getSource()
                                    .sendFeedback(Component.literal("§f  " + tex.get("name").getAsString() + " §7(id=" + tex.get("textureId").getAsInt() + ")"));
                              }
                           }
                        } catch (Exception e) {
                           ctx.getSource().sendFeedback(Component.literal("§cFailed to list textures: " + e.getMessage()));
                        }
                        return 1;
                     })
                  )
            )
            // /viewfinder patched_shaders
            .then(
               ClientCommands.literal("patched_shaders").executes(ctx -> {
                  try {
                     boolean debug = Iris.getIrisConfig().areDebugOptionsEnabled();
                     ctx.getSource().sendFeedback(Component.literal("§6Debug mode: " + (debug ? "§aenabled" : "§cdisabled")));
                     Path patchedDir = Minecraft.getInstance().gameDirectory.toPath().resolve("patched_shaders");
                     if (Files.exists(patchedDir) && Files.isDirectory(patchedDir)) {
                        ctx.getSource().sendFeedback(Component.literal("§fPath: " + patchedDir));
                        try (Stream<Path> stream = Files.list(patchedDir)) {
                           var files = stream.toList();
                           ctx.getSource().sendFeedback(Component.literal("§6Files: §f" + files.size()));
                           for (Path p : files) {
                              ctx.getSource().sendFeedback(Component.literal("§f  " + p.getFileName()));
                           }
                        }
                     } else {
                        ctx.getSource().sendFeedback(Component.literal("§eNo patched shaders directory found"));
                     }
                  } catch (Exception e) {
                     ctx.getSource().sendFeedback(Component.literal("§cFailed to query patched shaders: " + e.getMessage()));
                  }
                  return 1;
               })
            )
      );
   }
}
