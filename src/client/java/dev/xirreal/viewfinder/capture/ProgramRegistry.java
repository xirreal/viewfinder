package dev.xirreal.viewfinder.capture;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL41C;
import org.lwjgl.opengl.GL43C;

public final class ProgramRegistry {
   private static final int MAX_BINARY_BYTES = 64 * 1024 * 1024;
   private static final Map<Integer, String> names = new LinkedHashMap<>();

   private ProgramRegistry() {}

   public static synchronized void register(int id, String name) {
      names.put(id, name);
   }

   public static synchronized void remove(int id) {
      names.remove(id);
   }

   public static synchronized void clear() {
      names.clear();
   }

   public static JsonObject inspect(Integer requestedId) {
      int id = requestedId == null ? GL20C.glGetInteger(GL20C.GL_CURRENT_PROGRAM) : requestedId;
      JsonObject result = new JsonObject();
      result.addProperty("programId", id);
      synchronized (ProgramRegistry.class) {
         if (names.containsKey(id)) result.addProperty("name", names.get(id));
      }
      if (id == 0 || !GL20C.glIsProgram(id)) {
         result.addProperty("linked", false);
         result.addProperty("error", id == 0 ? "No program is currently bound" : "Unknown OpenGL program id");
         return result;
      }

      result.addProperty("linked", GL20C.glGetProgrami(id, GL20C.GL_LINK_STATUS) == GL20C.GL_TRUE);
      result.addProperty("validated", GL20C.glGetProgrami(id, GL20C.GL_VALIDATE_STATUS) == GL20C.GL_TRUE);
      result.addProperty("infoLog", GL20C.glGetProgramInfoLog(id));
      result.add("uniforms", uniforms(id));
      result.add("uniformBlocks", interfaces(id, GL43C.GL_UNIFORM_BLOCK));
      result.add("shaderStorageBlocks", interfaces(id, GL43C.GL_SHADER_STORAGE_BLOCK));
      synchronized (ProgramRegistry.class) {
         JsonArray known = new JsonArray();
         names.forEach((programId, name) -> {
            JsonObject entry = new JsonObject();
            entry.addProperty("id", programId);
            entry.addProperty("name", name);
            known.add(entry);
         });
         result.add("knownPrograms", known);
      }
      return result;
   }

   public static JsonObject dumpBinary(Integer requestedId, Path output) throws IOException {
      int id = requestedId == null ? GL20C.glGetInteger(GL20C.GL_CURRENT_PROGRAM) : requestedId;
      if (id == 0) throw new IllegalArgumentException("No program is currently bound");
      if (!GL20C.glIsProgram(id)) throw new IllegalArgumentException("Unknown OpenGL program id: " + id);
      if (GL20C.glGetProgrami(id, GL20C.GL_LINK_STATUS) != GL20C.GL_TRUE) {
         throw new IllegalArgumentException("OpenGL program " + id + " is not linked");
      }
      if (GL20C.glGetInteger(GL41C.GL_NUM_PROGRAM_BINARY_FORMATS) < 1) {
         throw new IllegalStateException("The OpenGL driver exposes no program binary formats");
      }

      int capacity = GL20C.glGetProgrami(id, GL41C.GL_PROGRAM_BINARY_LENGTH);
      if (capacity < 1) throw new IllegalStateException("The OpenGL driver returned an empty program binary");
      if (capacity > MAX_BINARY_BYTES) throw new IllegalStateException("Program binary exceeds the 64 MiB capture limit");

      ByteBuffer binary = MemoryUtil.memAlloc(capacity);
      try (MemoryStack stack = MemoryStack.stackPush()) {
         var length = stack.callocInt(1);
         var format = stack.callocInt(1);
         GL41C.glGetProgramBinary(id, length, format, binary);
         int bytes = length.get(0);
         if (bytes < 1 || bytes > capacity) throw new IllegalStateException("The OpenGL driver returned an invalid program binary length");

         binary.limit(bytes);
         try (FileChannel file = FileChannel.open(output, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            while (binary.hasRemaining()) file.write(binary);
         }

         JsonObject result = new JsonObject();
         result.addProperty("success", true);
         result.addProperty("programId", id);
         synchronized (ProgramRegistry.class) {
            if (names.containsKey(id)) result.addProperty("name", names.get(id));
         }
         result.addProperty("binaryFormat", format.get(0));
         result.addProperty("binaryFormatHex", "0x" + Integer.toHexString(format.get(0)).toUpperCase());
         result.addProperty("totalBytes", bytes);
         result.addProperty("retrievableHint", GL20C.glGetProgrami(id, GL41C.GL_PROGRAM_BINARY_RETRIEVABLE_HINT) == GL20C.GL_TRUE);
         result.addProperty("vendor", GL11C.glGetString(GL11C.GL_VENDOR));
         result.addProperty("renderer", GL11C.glGetString(GL11C.GL_RENDERER));
         result.addProperty("openGlVersion", GL11C.glGetString(GL11C.GL_VERSION));
         result.addProperty("path", output.toAbsolutePath().toString());
         result.addProperty("note", "NVIDIA proprietary drivers commonly embed readable NVIDIA pseudo-assembly; AMD binaries are effectively meaningless for this workflow. The format remains driver-specific and unstable across GPUs or driver versions");
         return result;
      } finally {
         MemoryUtil.memFree(binary);
      }
   }

   private static JsonArray uniforms(int program) {
      JsonArray result = new JsonArray();
      int count = GL20C.glGetProgrami(program, GL20C.GL_ACTIVE_UNIFORMS);
      int maxLength = Math.max(1, GL20C.glGetProgrami(program, GL20C.GL_ACTIVE_UNIFORM_MAX_LENGTH));
      try (MemoryStack stack = MemoryStack.stackPush()) {
         var size = stack.mallocInt(1);
         var type = stack.mallocInt(1);
         for (int index = 0; index < count; index++) {
            String name = GL20C.glGetActiveUniform(program, index, maxLength, size, type);
            JsonObject uniform = new JsonObject();
            uniform.addProperty("name", name);
            uniform.addProperty("location", GL20C.glGetUniformLocation(program, name));
            uniform.addProperty("size", size.get(0));
            uniform.addProperty("type", type.get(0));
            result.add(uniform);
         }
      }
      return result;
   }

   private static JsonArray interfaces(int program, int programInterface) {
      JsonArray result = new JsonArray();
      int count = GL43C.glGetProgramInterfacei(program, programInterface, GL43C.GL_ACTIVE_RESOURCES);
      for (int index = 0; index < count; index++) {
         JsonObject block = new JsonObject();
         block.addProperty("name", GL43C.glGetProgramResourceName(program, programInterface, index));
         block.addProperty("index", index);
         result.add(block);
      }
      return result;
   }
}
