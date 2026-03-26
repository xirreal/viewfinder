package dev.xirreal.viewfinder.capture;

import com.google.gson.JsonObject;
import dev.xirreal.viewfinder.Viewfinder;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import org.lwjgl.opengl.GL43C;
import org.lwjgl.system.MemoryUtil;

public class SSBODumper {

   public static JsonObject dumpBuffer(int bufferId, int index, String outputPath) {
      JsonObject result = new JsonObject();

      int previousBuffer = GL43C.glGetInteger(GL43C.GL_SHADER_STORAGE_BUFFER_BINDING);

      ByteBuffer data = null;
      try {
         GL43C.glMemoryBarrier(GL43C.GL_SHADER_STORAGE_BARRIER_BIT);

         GL43C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, bufferId);

         int size = GL43C.glGetBufferParameteri(GL43C.GL_SHADER_STORAGE_BUFFER, GL43C.GL_BUFFER_SIZE);
         if (size <= 0) {
            result.addProperty("error", "Buffer " + bufferId + " is empty or invalid.");
            return result;
         }

         data = MemoryUtil.memAlloc(size);

         GL43C.glGetBufferSubData(GL43C.GL_SHADER_STORAGE_BUFFER, 0, data);

         File outputFile = new File(outputPath);
         if (outputFile.getParentFile() != null) {
            outputFile.getParentFile().mkdirs();
         }

         try (FileOutputStream fos = new FileOutputStream(outputFile); FileChannel channel = fos.getChannel()) {
            channel.write(data);
         }

         result.addProperty("success", true);
         result.addProperty("path", outputFile.getAbsolutePath());
         result.addProperty("bufferId", bufferId);
         result.addProperty("totalBytes", size);
      } catch (Exception e) {
         Viewfinder.LOGGER.error("Failed to dump raw SSBO {}", bufferId, e);
         result.addProperty("error", e.getMessage());
      } finally {
         if (data != null) {
            MemoryUtil.memFree(data);
         }
         GL43C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, previousBuffer);
      }

      return result;
   }
}
