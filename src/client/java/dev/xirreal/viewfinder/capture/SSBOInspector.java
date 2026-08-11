package dev.xirreal.viewfinder.capture;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.lwjgl.opengl.GL43C;
import org.lwjgl.system.MemoryUtil;

public class SSBOInspector {

   private static final int DEFAULT_PREVIEW_BYTES = 256;
   private static final int MAX_PREVIEW_BYTES = 64 * 1024;
   private static final int MAX_PREVIEW_VALUES = 32;

   public static int defaultPreviewBytes() {
      return DEFAULT_PREVIEW_BYTES;
   }

   public static JsonObject inspectIndex(int index, int requestedBytes) {
      int bufferId = SSBOResolver.resolveBufferId(index);
      if (bufferId == -1) {
         JsonObject error = new JsonObject();
         error.addProperty("error", "No SSBO found at index " + index);
         return error;
      }

      return inspectBuffer(bufferId, index, requestedBytes);
   }

   public static JsonObject inspectBuffer(int bufferId, int index, int requestedBytes) {
      JsonObject result = new JsonObject();
      result.addProperty("index", index);
      result.addProperty("bufferId", bufferId);

      int previousBuffer = GL43C.glGetInteger(GL43C.GL_SHADER_STORAGE_BUFFER_BINDING);
      ByteBuffer data = null;
      try {
         GL43C.glMemoryBarrier(GL43C.GL_SHADER_STORAGE_BARRIER_BIT);
         GL43C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, bufferId);

         int size = GL43C.glGetBufferParameteri(GL43C.GL_SHADER_STORAGE_BUFFER, GL43C.GL_BUFFER_SIZE);
         result.addProperty("totalBytes", size);
         if (size <= 0) {
            result.addProperty("error", "Buffer " + bufferId + " is empty or invalid");
            return result;
         }

         int previewBytes = Math.min(size, Math.max(1, Math.min(requestedBytes, MAX_PREVIEW_BYTES)));
         data = MemoryUtil.memAlloc(previewBytes).order(ByteOrder.nativeOrder());
         GL43C.glGetBufferSubData(GL43C.GL_SHADER_STORAGE_BUFFER, 0, data);

         result.addProperty("inspectedBytes", previewBytes);
         result.addProperty("maxPreviewBytes", MAX_PREVIEW_BYTES);
         result.addProperty("truncated", previewBytes < size);
         result.add("preview", preview(data, previewBytes));
      } catch (Exception e) {
         result.addProperty("error", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
      } finally {
         if (data != null) {
            MemoryUtil.memFree(data);
         }
         GL43C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, previousBuffer);
      }

      return result;
   }

   private static JsonObject preview(ByteBuffer data, int inspectedBytes) {
      JsonObject preview = new JsonObject();
      preview.addProperty("hex", hexPreview(data, inspectedBytes));
      preview.add("uint8", uint8Preview(data, inspectedBytes));

      int intCount = inspectedBytes / Integer.BYTES;
      int valueCount = Math.min(intCount, MAX_PREVIEW_VALUES);
      preview.add("int32", int32Preview(data, valueCount));
      preview.add("uint32", uint32Preview(data, valueCount));
      preview.add("float32", float32Preview(data, valueCount));
      preview.add("float32Stats", floatStats(data, intCount));
      return preview;
   }

   private static String hexPreview(ByteBuffer data, int inspectedBytes) {
      int count = Math.min(inspectedBytes, 128);
      StringBuilder builder = new StringBuilder(count * 3);
      for (int i = 0; i < count; i++) {
         if (i > 0) {
            builder.append(' ');
         }
         int value = Byte.toUnsignedInt(data.get(i));
         if (value < 0x10) {
            builder.append('0');
         }
         builder.append(Integer.toHexString(value));
      }
      return builder.toString();
   }

   private static JsonArray uint8Preview(ByteBuffer data, int inspectedBytes) {
      int count = Math.min(inspectedBytes, MAX_PREVIEW_VALUES);
      JsonArray values = new JsonArray();
      for (int i = 0; i < count; i++) {
         values.add(Byte.toUnsignedInt(data.get(i)));
      }
      return values;
   }

   private static JsonArray int32Preview(ByteBuffer data, int valueCount) {
      JsonArray values = new JsonArray();
      for (int i = 0; i < valueCount; i++) {
         values.add(data.getInt(i * Integer.BYTES));
      }
      return values;
   }

   private static JsonArray uint32Preview(ByteBuffer data, int valueCount) {
      JsonArray values = new JsonArray();
      for (int i = 0; i < valueCount; i++) {
         values.add(Integer.toUnsignedLong(data.getInt(i * Integer.BYTES)));
      }
      return values;
   }

   private static JsonArray float32Preview(ByteBuffer data, int valueCount) {
      JsonArray values = new JsonArray();
      for (int i = 0; i < valueCount; i++) {
         float value = data.getFloat(i * Float.BYTES);
         if (Float.isFinite(value)) {
            values.add(value);
         } else if (Float.isNaN(value)) {
            values.add("NaN");
         } else {
            values.add(value > 0.0f ? "Infinity" : "-Infinity");
         }
      }
      return values;
   }

   private static JsonObject floatStats(ByteBuffer data, int valueCount) {
      JsonObject stats = new JsonObject();
      double min = Double.POSITIVE_INFINITY;
      double max = Double.NEGATIVE_INFINITY;
      double sum = 0.0;
      int finiteCount = 0;
      int nanCount = 0;
      int infiniteCount = 0;

      for (int i = 0; i < valueCount; i++) {
         float value = data.getFloat(i * Float.BYTES);
         if (Float.isNaN(value)) {
            nanCount++;
         } else if (Float.isInfinite(value)) {
            infiniteCount++;
         } else {
            min = Math.min(min, value);
            max = Math.max(max, value);
            sum += value;
            finiteCount++;
         }
      }

      stats.addProperty("valuesRead", valueCount);
      stats.addProperty("finiteCount", finiteCount);
      stats.addProperty("nanCount", nanCount);
      stats.addProperty("infiniteCount", infiniteCount);
      if (finiteCount > 0) {
         stats.addProperty("min", min);
         stats.addProperty("max", max);
         stats.addProperty("avg", sum / finiteCount);
      }
      return stats;
   }
}
