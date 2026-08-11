package dev.xirreal.viewfinder.capture;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.xirreal.viewfinder.mixin.client.ShaderStorageBufferAccessor;
import dev.xirreal.viewfinder.mixin.client.ShaderStorageBufferHolderAccessor;

import java.util.List;
import org.lwjgl.opengl.GL43C;

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

    public static int resolveBufferSize(int bufferId) {
        int previousBuffer = GL43C.glGetInteger(GL43C.GL_SHADER_STORAGE_BUFFER_BINDING);
        try {
            GL43C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, bufferId);
            return GL43C.glGetBufferParameteri(GL43C.GL_SHADER_STORAGE_BUFFER, GL43C.GL_BUFFER_SIZE);
        } finally {
            GL43C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, previousBuffer);
        }
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
            try {
                entry.addProperty("sizeBytes", resolveBufferSize(accessor.getId()));
            } catch (Exception e) {
                entry.addProperty("sizeError", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            }
            buffers.add(entry);
        }
        result.add("buffers", buffers);
        return result;
    }
}
