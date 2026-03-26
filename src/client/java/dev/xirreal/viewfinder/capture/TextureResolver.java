package dev.xirreal.viewfinder.capture;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.xirreal.viewfinder.mixin.client.IrisRenderingPipelineAccessor;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.gl.texture.TextureAccess;
import net.irisshaders.iris.pipeline.CustomTextureManager;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.shaderpack.texture.TextureStage;
import net.irisshaders.iris.targets.RenderTarget;
import net.irisshaders.iris.targets.RenderTargets;

import java.util.LinkedHashMap;
import java.util.Map;

public class TextureResolver {

    public static int resolveTexture(String name) {
        var pipeline = Iris.getPipelineManager().getPipelineNullable();
        if (!(pipeline instanceof IrisRenderingPipeline irisPipeline)) {
            return -1;
        }

        IrisRenderingPipelineAccessor accessor = (IrisRenderingPipelineAccessor) irisPipeline;

        // Try colortex0-29
        if (name.startsWith("colortex")) {
            try {
                int index = Integer.parseInt(name.substring("colortex".length()));
                RenderTargets targets = accessor.getRenderTargets();
                RenderTarget target = targets.get(index);
                if (target != null) {
                    return target.getMainTexture();
                }
            } catch (NumberFormatException | IndexOutOfBoundsException ignored) {}
        }

        // Try custom textures across all stages
        CustomTextureManager customTexMgr = accessor.getCustomTextureManager();
        for (TextureStage stage : TextureStage.values()) {
            Object2ObjectMap<String, TextureAccess> stageTextures = customTexMgr.getCustomTextureIdMap(stage);
            TextureAccess texAccess = stageTextures.get(name);
            if (texAccess != null) {
                return texAccess.getTextureId().getAsInt();
            }
        }

        // Try iris custom textures
        Object2ObjectMap<String, TextureAccess> irisCustom = customTexMgr.getIrisCustomTextures();
        TextureAccess texAccess = irisCustom.get(name);
        if (texAccess != null) {
            return texAccess.getTextureId().getAsInt();
        }

        // Try noise texture
        if ("noisetex".equals(name)) {
            TextureAccess noise = customTexMgr.getNoiseTexture();
            if (noise != null) {
                return noise.getTextureId().getAsInt();
            }
        }

        return -1;
    }

    public static JsonObject listTextures() {
        JsonObject result = new JsonObject();

        var pipeline = Iris.getPipelineManager().getPipelineNullable();
        if (!(pipeline instanceof IrisRenderingPipeline irisPipeline)) {
            result.addProperty("error", "No Iris pipeline active");
            return result;
        }

        IrisRenderingPipelineAccessor accessor = (IrisRenderingPipelineAccessor) irisPipeline;

        // Colortex buffers
        JsonArray colortexArray = new JsonArray();
        RenderTargets targets = accessor.getRenderTargets();
        for (int i = 0; i < targets.getRenderTargetCount(); i++) {
            RenderTarget target = targets.get(i);
            if (target != null) {
                JsonObject tex = new JsonObject();
                tex.addProperty("name", "colortex" + i);
                tex.addProperty("textureId", target.getMainTexture());
                tex.addProperty("width", target.getWidth());
                tex.addProperty("height", target.getHeight());
                colortexArray.add(tex);
            }
        }
        result.add("colortex", colortexArray);

        // Custom textures
        JsonArray customArray = new JsonArray();
        CustomTextureManager customTexMgr = accessor.getCustomTextureManager();

        Map<String, Integer> seen = new LinkedHashMap<>();
        for (TextureStage stage : TextureStage.values()) {
            Object2ObjectMap<String, TextureAccess> stageTextures = customTexMgr.getCustomTextureIdMap(stage);
            for (var entry : stageTextures.entrySet()) {
                if (!seen.containsKey(entry.getKey())) {
                    seen.put(entry.getKey(), entry.getValue().getTextureId().getAsInt());
                }
            }
        }
        for (var entry : customTexMgr.getIrisCustomTextures().entrySet()) {
            if (!seen.containsKey(entry.getKey())) {
                seen.put(entry.getKey(), entry.getValue().getTextureId().getAsInt());
            }
        }
        for (var entry : seen.entrySet()) {
            JsonObject tex = new JsonObject();
            tex.addProperty("name", entry.getKey());
            tex.addProperty("textureId", entry.getValue());
            customArray.add(tex);
        }
        result.add("custom", customArray);

        return result;
    }
}
