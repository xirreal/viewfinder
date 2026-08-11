package dev.xirreal.viewfinder.capture;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import org.lwjgl.opengl.GL43C;
import org.lwjgl.system.MemoryUtil;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;

public class TextureInspector {

   private static final long MAX_TEXTURE_READ_BYTES = 64L * 1024L * 1024L;
   private static final int DEFAULT_SAMPLE_COUNT = 4096;
   private static final int MAX_SAMPLE_COUNT = 1_000_000;

   public static int defaultSampleCount() {
      return DEFAULT_SAMPLE_COUNT;
   }

   public static JsonObject inspectByName(String name, int maxSamples) {
      int textureId = TextureResolver.resolveTexture(name);
      if (textureId == -1) {
         JsonObject error = new JsonObject();
         error.addProperty("error", "Unknown texture: " + name);
         return error;
      }

      JsonObject result = inspectTexture(textureId, maxSamples);
      result.addProperty("name", name);
      return result;
   }

   public static JsonObject inspectTexture(int textureId, int maxSamples) {
      return inspectTexture(textureId, maxSamples, -1, -1, 0);
   }

   public static JsonObject inspectByName(String name, int maxSamples, int x, int y, int z) {
      int textureId = TextureResolver.resolveTexture(name);
      if (textureId == -1) {
         JsonObject error = new JsonObject();
         error.addProperty("error", "Unknown texture: " + name);
         return error;
      }

      JsonObject result = inspectTexture(textureId, maxSamples, x, y, z);
      result.addProperty("name", name);
      return result;
   }

   public static JsonObject inspectTexture(int textureId, int maxSamples, int x, int y, int z) {
      JsonObject result = describeTexture(textureId);
      if (result.has("error")) {
         return result;
      }

      int target = result.get("target").getAsInt();
      int width = result.get("width").getAsInt();
      int height = result.get("height").getAsInt();
      int depth = result.get("depth").getAsInt();
      boolean integer = result.get("integer").getAsBoolean();
      boolean signed = result.get("signed").getAsBoolean();
      int components = result.get("components").getAsInt();
      int readComponents = integer ? components : 4;
      long totalPixels = (long) width * height * depth;
      long estimatedBytes = totalPixels * readComponents * Integer.BYTES;

      result.addProperty("readComponents", readComponents);
      result.addProperty("estimatedReadBytes", estimatedBytes);
      result.addProperty("maxReadBytes", MAX_TEXTURE_READ_BYTES);
      result.addProperty("maxSampleCount", MAX_SAMPLE_COUNT);

      if (estimatedBytes <= 0L || estimatedBytes > MAX_TEXTURE_READ_BYTES || estimatedBytes > Integer.MAX_VALUE) {
         result.addProperty("statsAvailable", false);
         result.addProperty("statsSkippedReason", "estimated read size exceeds bounded probe limit");
         return result;
      }

      int previousTexture = GL43C.glGetInteger(TextureDumper.getBindingQuery(target));
      try {
         GL43C.glBindTexture(target, textureId);
         if (integer) {
            inspectIntegerTexture(result, target, width, height, depth, components, signed, maxSamples, x, y, z);
         } else {
            inspectFloatTexture(result, target, width, height, depth, maxSamples, x, y, z);
         }
      } catch (Exception e) {
         result.addProperty("error", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
      } finally {
         GL43C.glBindTexture(target, previousTexture);
      }

      return result;
   }

   public static JsonObject describeTexture(int textureId) {
      JsonObject result = new JsonObject();
      result.addProperty("textureId", textureId);

      int target = TextureDumper.detectTarget(textureId);
      int previousTexture = GL43C.glGetInteger(TextureDumper.getBindingQuery(target));
      try {
         GL43C.glBindTexture(target, textureId);

         int width = GL43C.glGetTexLevelParameteri(target, 0, GL43C.GL_TEXTURE_WIDTH);
         int height = GL43C.glGetTexLevelParameteri(target, 0, GL43C.GL_TEXTURE_HEIGHT);
         int depth = target == GL43C.GL_TEXTURE_3D ? GL43C.glGetTexLevelParameteri(target, 0, GL43C.GL_TEXTURE_DEPTH) : 1;
         if (width <= 0 || height <= 0) {
            result.addProperty("error", "Texture " + textureId + " has invalid dimensions: " + width + "x" + height);
            return result;
         }

         int internalFormat = GL43C.glGetTexLevelParameteri(target, 0, GL43C.GL_TEXTURE_INTERNAL_FORMAT);
         boolean integer = TextureDumper.isIntegerFormat(internalFormat);
         result.addProperty("target", target);
         result.addProperty("targetName", targetName(target));
         result.addProperty("width", width);
         result.addProperty("height", height);
         result.addProperty("depth", depth);
         result.addProperty("totalPixels", (long) width * height * depth);
         result.addProperty("internalFormat", internalFormat);
         result.addProperty("formatName", TextureDumper.getFormatName(internalFormat));
         result.addProperty("components", TextureDumper.getComponentCount(internalFormat));
         result.addProperty("integer", integer);
         result.addProperty("signed", integer && TextureDumper.isSignedFormat(internalFormat));
         result.addProperty("statsAvailable", false);
      } catch (Exception e) {
         result.addProperty("error", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
      } finally {
         GL43C.glBindTexture(target, previousTexture);
      }

      return result;
   }

   private static void inspectIntegerTexture(
      JsonObject result,
      int target,
      int width,
      int height,
      int depth,
      int components,
      boolean signed,
      int maxSamples,
      int x,
      int y,
      int z
   ) {
      long totalPixels = (long) width * height * depth;
      int values = Math.toIntExact(totalPixels * components);
      IntBuffer buffer = MemoryUtil.memAllocInt(values);
      try {
         GL43C.glGetTexImage(target, 0, integerPixelFormat(components), signed ? GL43C.GL_INT : GL43C.GL_UNSIGNED_INT, buffer);
         result.add("firstPixel", integerPixel(buffer, components, 0, signed));
         result.add("centerPixel", integerPixel(buffer, components, totalPixels / 2L, signed));
         if (x >= 0 && y >= 0) {
            addRequestedIntegerPixel(result, buffer, width, height, depth, components, signed, x, y, z);
         }
         addIntegerStats(result, buffer, totalPixels, components, signed, maxSamples);
      } finally {
         MemoryUtil.memFree(buffer);
      }
   }

   private static void inspectFloatTexture(JsonObject result, int target, int width, int height, int depth, int maxSamples, int x, int y, int z) {
      long totalPixels = (long) width * height * depth;
      int values = Math.toIntExact(totalPixels * 4L);
      FloatBuffer buffer = MemoryUtil.memAllocFloat(values);
      try {
         GL43C.glGetTexImage(target, 0, GL43C.GL_RGBA, GL43C.GL_FLOAT, buffer);
         result.add("firstPixel", floatPixel(buffer, 0));
         result.add("centerPixel", floatPixel(buffer, totalPixels / 2L));
         if (x >= 0 && y >= 0) {
            addRequestedFloatPixel(result, buffer, width, height, depth, x, y, z);
         }
         addFloatStats(result, buffer, totalPixels, maxSamples);
      } finally {
         MemoryUtil.memFree(buffer);
      }
   }

   private static void addIntegerStats(JsonObject result, IntBuffer buffer, long totalPixels, int components, boolean signed, int maxSamples) {
      int samples = sampleCount(totalPixels, maxSamples);
      ChannelAccumulator[] accumulators = new ChannelAccumulator[components];
      for (int i = 0; i < components; i++) {
         accumulators[i] = new ChannelAccumulator();
      }

      for (int sample = 0; sample < samples; sample++) {
         long pixel = samplePixel(totalPixels, samples, sample);
         int offset = Math.toIntExact(pixel * components);
         for (int component = 0; component < components; component++) {
            double value = signed ? buffer.get(offset + component) : Integer.toUnsignedLong(buffer.get(offset + component));
            accumulators[component].add(value);
         }
      }

      result.addProperty("statsAvailable", true);
      result.addProperty("sampledPixels", samples);
      result.addProperty("truncated", samples < totalPixels);
      result.add("channels", channelStats(accumulators));
   }

   private static void addFloatStats(JsonObject result, FloatBuffer buffer, long totalPixels, int maxSamples) {
      int samples = sampleCount(totalPixels, maxSamples);
      ChannelAccumulator[] accumulators = new ChannelAccumulator[4];
      for (int i = 0; i < 4; i++) {
         accumulators[i] = new ChannelAccumulator();
      }

      for (int sample = 0; sample < samples; sample++) {
         long pixel = samplePixel(totalPixels, samples, sample);
         int offset = Math.toIntExact(pixel * 4L);
         for (int component = 0; component < 4; component++) {
            accumulators[component].add(buffer.get(offset + component));
         }
      }

      result.addProperty("statsAvailable", true);
      result.addProperty("sampledPixels", samples);
      result.addProperty("truncated", samples < totalPixels);
      result.add("channels", channelStats(accumulators));
   }

   private static void addRequestedIntegerPixel(
      JsonObject result,
      IntBuffer buffer,
      int width,
      int height,
      int depth,
      int components,
      boolean signed,
      int x,
      int y,
      int z
   ) {
      JsonObject requested = requestedPixelHeader(width, height, depth, x, y, z);
      if (requested.get("valid").getAsBoolean()) {
         requested.add("value", integerPixel(buffer, components, linearPixel(width, height, x, y, z), signed));
      }
      result.add("requestedPixel", requested);
   }

   private static void addRequestedFloatPixel(JsonObject result, FloatBuffer buffer, int width, int height, int depth, int x, int y, int z) {
      JsonObject requested = requestedPixelHeader(width, height, depth, x, y, z);
      if (requested.get("valid").getAsBoolean()) {
         requested.add("value", floatPixel(buffer, linearPixel(width, height, x, y, z)));
      }
      result.add("requestedPixel", requested);
   }

   private static JsonObject requestedPixelHeader(int width, int height, int depth, int x, int y, int z) {
      JsonObject requested = new JsonObject();
      requested.addProperty("x", x);
      requested.addProperty("y", y);
      requested.addProperty("z", z);
      boolean valid = x >= 0 && x < width && y >= 0 && y < height && z >= 0 && z < depth;
      requested.addProperty("valid", valid);
      if (!valid) {
         requested.addProperty("error", "Requested pixel is outside texture bounds");
      }
      return requested;
   }

   private static JsonArray integerPixel(IntBuffer buffer, int components, long pixel, boolean signed) {
      JsonArray values = new JsonArray();
      int offset = Math.toIntExact(pixel * components);
      for (int component = 0; component < components; component++) {
         if (signed) {
            values.add(buffer.get(offset + component));
         } else {
            values.add(Integer.toUnsignedLong(buffer.get(offset + component)));
         }
      }
      return values;
   }

   private static JsonArray floatPixel(FloatBuffer buffer, long pixel) {
      JsonArray values = new JsonArray();
      int offset = Math.toIntExact(pixel * 4L);
      for (int component = 0; component < 4; component++) {
         float value = buffer.get(offset + component);
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

   private static JsonArray channelStats(ChannelAccumulator[] accumulators) {
      JsonArray channels = new JsonArray();
      for (int i = 0; i < accumulators.length; i++) {
         ChannelAccumulator accumulator = accumulators[i];
         JsonObject channel = new JsonObject();
         channel.addProperty("index", i);
         channel.addProperty("finiteCount", accumulator.finiteCount);
         channel.addProperty("nanCount", accumulator.nanCount);
         channel.addProperty("infiniteCount", accumulator.infiniteCount);
         if (accumulator.finiteCount > 0) {
            channel.addProperty("min", accumulator.min);
            channel.addProperty("max", accumulator.max);
            channel.addProperty("avg", accumulator.sum / accumulator.finiteCount);
         } else {
            channel.add("min", JsonNull.INSTANCE);
            channel.add("max", JsonNull.INSTANCE);
            channel.add("avg", JsonNull.INSTANCE);
         }
         channels.add(channel);
      }
      return channels;
   }

   private static int sampleCount(long totalPixels, int maxSamples) {
      if (totalPixels <= 0L) {
         return 0;
      }
      int clampedMax = Math.max(1, Math.min(maxSamples, MAX_SAMPLE_COUNT));
      return (int) Math.min(totalPixels, clampedMax);
   }

   private static long samplePixel(long totalPixels, int samples, int sample) {
      if (samples <= 1) {
         return 0L;
      }
      return Math.min(totalPixels - 1L, (long) Math.floor(sample * (totalPixels / (double) samples)));
   }

   private static long linearPixel(int width, int height, int x, int y, int z) {
      return ((long) z * height + y) * width + x;
   }

   private static int integerPixelFormat(int components) {
      return switch (components) {
         case 1 -> GL43C.GL_RED_INTEGER;
         case 2 -> GL43C.GL_RG_INTEGER;
         case 3 -> GL43C.GL_RGB_INTEGER;
         default -> GL43C.GL_RGBA_INTEGER;
      };
   }

   private static String targetName(int target) {
      return switch (target) {
         case GL43C.GL_TEXTURE_2D -> "GL_TEXTURE_2D";
         case GL43C.GL_TEXTURE_3D -> "GL_TEXTURE_3D";
         default -> "0x" + Integer.toHexString(target);
      };
   }

   private static class ChannelAccumulator {

      private double min = Double.POSITIVE_INFINITY;
      private double max = Double.NEGATIVE_INFINITY;
      private double sum;
      private int finiteCount;
      private int nanCount;
      private int infiniteCount;

      private void add(double value) {
         if (Double.isNaN(value)) {
            nanCount++;
            return;
         }
         if (Double.isInfinite(value)) {
            infiniteCount++;
            return;
         }
         min = Math.min(min, value);
         max = Math.max(max, value);
         sum += value;
         finiteCount++;
      }
   }
}
