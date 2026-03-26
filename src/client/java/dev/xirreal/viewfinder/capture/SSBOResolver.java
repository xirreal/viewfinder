package dev.xirreal.viewfinder.capture;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.xirreal.viewfinder.mixin.client.ShaderStorageBufferAccessor;
import dev.xirreal.viewfinder.mixin.client.ShaderStorageBufferHolderAccessor;

import java.util.List;

public class SSBOResolver {

    public static int resolveBufferId(int index) {
        List<?> activeBuffers = ShaderStorageBufferHolderAccessor.getActiveBuffers();
        for (Object buf : activeBuffers) {
            ShaderStorageBufferAccessor accessor = (ShaderStorageBufferAccessor) buf;
            if (accessor.getIndex() == index) {
                return accessor.getId();
            }
        }
        return -1;
    }

    public static JsonObject listBuffers() {
        JsonObject result = new JsonObject();
        List<?> activeBuffers = ShaderStorageBufferHolderAccessor.getActiveBuffers();

        JsonArray buffers = new JsonArray();
        for (Object buf : activeBuffers) {
            ShaderStorageBufferAccessor accessor = (ShaderStorageBufferAccessor) buf;
            JsonObject entry = new JsonObject();
            entry.addProperty("index", accessor.getIndex());
            entry.addProperty("glId", accessor.getId());
            buffers.add(entry);
        }
        result.add("buffers", buffers);
        return result;
    }
}
