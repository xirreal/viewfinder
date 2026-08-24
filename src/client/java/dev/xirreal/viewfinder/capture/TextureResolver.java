package dev.xirreal.viewfinder.capture;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.opengl.GlTexture;
import dev.xirreal.viewfinder.mixin.client.IrisRenderingPipelineAccessor;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.gl.texture.TextureAccess;
import net.irisshaders.iris.pipeline.CustomTextureManager;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.shaderpack.texture.TextureStage;
import net.irisshaders.iris.targets.RenderTarget;
import net.irisshaders.iris.targets.RenderTargets;
import net.irisshaders.iris.shadows.ShadowRenderTargets;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
                String suffix = name.substring("colortex".length());
                boolean alt = suffix.endsWith("_alt");
                int index = Integer.parseInt(alt ? suffix.substring(0, suffix.length() - 4) : suffix.replace("_main", ""));
                RenderTargets targets = accessor.getRenderTargets();
                RenderTarget target = targets.get(index);
                if (target != null) {
                    return alt ? target.getAltTexture() : target.getMainTexture();
                }
            } catch (NumberFormatException | IndexOutOfBoundsException ignored) {}
        }

        if ("depthtex0".equals(name)) {
            return glId(accessor.getRenderTargets().getDepthTexture());
        }

        ShadowRenderTargets shadows = accessor.getShadowRenderTargets();
        if (shadows != null) {
            if ("shadowtex0".equals(name)) return glId(shadows.getDepthTexture());
            if ("shadowtex1".equals(name)) return glId(shadows.getDepthTextureNoTranslucents());
            if (name.startsWith("shadowcolor")) {
                try {
                    return shadows.getColorTextureId(Integer.parseInt(name.substring("shadowcolor".length())));
                } catch (NumberFormatException | IndexOutOfBoundsException ignored) {}
            }
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
                tex.addProperty("alternateTextureId", target.getAltTexture());
                tex.addProperty("width", target.getWidth());
                tex.addProperty("height", target.getHeight());
                addTextureMetadata(tex, target.getMainTexture());
                colortexArray.add(tex);
            }
        }
        result.add("colortex", colortexArray);

        JsonArray depthArray = new JsonArray();
        addNamedTexture(depthArray, "depthtex0", glId(targets.getDepthTexture()));
        result.add("depth", depthArray);

        JsonArray shadowArray = new JsonArray();
        ShadowRenderTargets shadows = accessor.getShadowRenderTargets();
        if (shadows != null) {
            addNamedTexture(shadowArray, "shadowtex0", glId(shadows.getDepthTexture()));
            addNamedTexture(shadowArray, "shadowtex1", glId(shadows.getDepthTextureNoTranslucents()));
            for (int i = 0; i < shadows.getNumColorTextures(); i++) addNamedTexture(shadowArray, "shadowcolor" + i, shadows.getColorTextureId(i));
        }
        result.add("shadow", shadowArray);

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
            addTextureMetadata(tex, entry.getValue());
            customArray.add(tex);
        }
        result.add("custom", customArray);

        TextureAccess noise = customTexMgr.getNoiseTexture();
        if (noise != null) addNamedTexture(customArray, "noisetex", noise.getTextureId().getAsInt());

        return result;
    }

    public static JsonArray listTextureNames() {
        JsonObject textures = listTextures();
        LinkedHashSet<String> names = new LinkedHashSet<>();
        for (String group : new String[] { "colortex", "depth", "shadow", "custom" }) {
            if (!textures.has(group) || !textures.get(group).isJsonArray()) continue;
            textures.getAsJsonArray(group).forEach(element -> {
                JsonObject texture = element.getAsJsonObject();
                if (!texture.has("name")) return;
                String name = texture.get("name").getAsString();
                names.add(name);
                if (group.equals("colortex") && texture.has("alternateTextureId")) names.add(name + "_alt");
            });
        }
        JsonArray result = new JsonArray();
        names.forEach(result::add);
        return result;
    }

    private static void addNamedTexture(JsonArray array, String name, int id) {
        if (id < 1) return;
        JsonObject texture = new JsonObject();
        texture.addProperty("name", name);
        texture.addProperty("textureId", id);
        addTextureMetadata(texture, id);
        array.add(texture);
    }

    private static int glId(Object texture) {
        return texture instanceof GlTexture glTexture ? glTexture.glId() : -1;
    }

    private static void addTextureMetadata(JsonObject texture, int textureId) {
        JsonObject metadata = TextureInspector.describeTexture(textureId);
        if (metadata.has("error")) {
            texture.addProperty("metadataError", metadata.get("error").getAsString());
            return;
        }

        for (String key : new String[] { "target", "targetName", "width", "height", "depth", "totalPixels", "internalFormat", "formatName", "components", "integer", "signed" }) {
            if (metadata.has(key)) {
                texture.add(key, metadata.get(key));
            }
        }
    }
}
