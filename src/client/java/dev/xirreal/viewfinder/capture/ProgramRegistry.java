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
   private static final int MAX_RECOVERY_PROGRAMS = 64;
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

   public static synchronized int resolve(String name) {
      if (name == null || name.isBlank()) throw new IllegalArgumentException("Program name must be non-empty");
      Integer match = null;
      for (Map.Entry<Integer, String> entry : names.entrySet()) {
         if (!name.equals(entry.getValue())) continue;
         if (match != null) {
            throw new IllegalArgumentException("Program name is ambiguous: " + name + " (ids " + match + " and " + entry.getKey() + ")");
         }
         match = entry.getKey();
      }
      if (match == null) throw new IllegalArgumentException("Unknown Iris program name: " + name);
      return match;
   }

   public static synchronized JsonObject listPrograms() {
      JsonObject result = new JsonObject();
      JsonArray programs = knownPrograms();
      result.add("programs", programs);
      result.addProperty("count", programs.size());
      return result;
   }

   public static JsonObject inspect(int id) {
      JsonObject result = new JsonObject();
      result.addProperty("programId", id);
      synchronized (ProgramRegistry.class) {
         if (names.containsKey(id)) result.addProperty("name", names.get(id));
      }
      if (id == 0 || !GL20C.glIsProgram(id)) {
         result.addProperty("linked", false);
         result.addProperty("error", "Unknown OpenGL program id: " + id);
         synchronized (ProgramRegistry.class) {
            result.addProperty("availableProgramCount", names.size());
            result.add("availablePrograms", knownPrograms(MAX_RECOVERY_PROGRAMS));
         }
         return result;
      }

      result.addProperty("linked", GL20C.glGetProgrami(id, GL20C.GL_LINK_STATUS) == GL20C.GL_TRUE);
      result.addProperty("validated", GL20C.glGetProgrami(id, GL20C.GL_VALIDATE_STATUS) == GL20C.GL_TRUE);
      result.addProperty("infoLog", GL20C.glGetProgramInfoLog(id));
      result.add("uniforms", uniforms(id));
      result.add("uniformBlocks", interfaces(id, GL43C.GL_UNIFORM_BLOCK));
      result.add("shaderStorageBlocks", interfaces(id, GL43C.GL_SHADER_STORAGE_BLOCK));
      return result;
   }

   public static JsonObject dumpBinary(int id, Path output) throws IOException {
      JsonObject driver = driverInfo();
      if (!driver.get("programBinaryDumpSupported").getAsBoolean()) {
         throw new IllegalStateException("Program binary dumps require NVIDIA's proprietary OpenGL driver; active vendor is "
            + driver.get("vendor").getAsString());
      }
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
         result.add("driver", driver);
         result.addProperty("vendor", driver.get("vendor").getAsString());
         result.addProperty("renderer", driver.get("renderer").getAsString());
         result.addProperty("openGlVersion", driver.get("openGlVersion").getAsString());
         result.addProperty("path", output.toAbsolutePath().toString());
         result.addProperty("note", "NVIDIA proprietary drivers commonly embed readable NVIDIA pseudo-assembly; AMD binaries are effectively meaningless for this workflow. The format remains driver-specific and unstable across GPUs or driver versions");
         return result;
      } finally {
         MemoryUtil.memFree(binary);
      }
   }

   public static JsonObject driverInfo() {
      String vendor = string(GL11C.GL_VENDOR);
      JsonObject driver = new JsonObject();
      driver.addProperty("vendor", vendor);
      driver.addProperty("renderer", string(GL11C.GL_RENDERER));
      driver.addProperty("openGlVersion", string(GL11C.GL_VERSION));
      driver.addProperty("programBinaryDumpSupported", vendor.equals("NVIDIA Corporation"));
      return driver;
   }

   private static JsonArray knownPrograms() {
      return knownPrograms(Integer.MAX_VALUE);
   }

   private static JsonArray knownPrograms(int limit) {
      JsonArray programs = new JsonArray();
      names.forEach((programId, name) -> {
         if (programs.size() >= limit) return;
         JsonObject entry = new JsonObject();
         entry.addProperty("id", programId);
         entry.addProperty("name", name);
         programs.add(entry);
      });
      return programs;
   }

   private static String string(int name) {
      String value = GL11C.glGetString(name);
      return value == null ? "unknown" : value;
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
