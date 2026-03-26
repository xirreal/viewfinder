package dev.xirreal.viewfinder.capture;

import com.google.gson.JsonObject;
import dev.xirreal.viewfinder.Viewfinder;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.channels.FileChannel;
import javax.imageio.ImageIO;
import org.lwjgl.opengl.GL43C;
import org.lwjgl.system.MemoryUtil;

public class TextureDumper {

   private static final int[] INTEGER_FORMATS = {
      GL43C.GL_R8I,
      GL43C.GL_RG8I,
      GL43C.GL_RGB8I,
      GL43C.GL_RGBA8I,
      GL43C.GL_R8UI,
      GL43C.GL_RG8UI,
      GL43C.GL_RGB8UI,
      GL43C.GL_RGBA8UI,
      GL43C.GL_R16I,
      GL43C.GL_RG16I,
      GL43C.GL_RGB16I,
      GL43C.GL_RGBA16I,
      GL43C.GL_R16UI,
      GL43C.GL_RG16UI,
      GL43C.GL_RGB16UI,
      GL43C.GL_RGBA16UI,
      GL43C.GL_R32I,
      GL43C.GL_RG32I,
      GL43C.GL_RGB32I,
      GL43C.GL_RGBA32I,
      GL43C.GL_R32UI,
      GL43C.GL_RG32UI,
      GL43C.GL_RGB32UI,
      GL43C.GL_RGBA32UI,
   };

   private static boolean isIntegerFormat(int internalFormat) {
      for (int fmt : INTEGER_FORMATS) {
         if (fmt == internalFormat) return true;
      }
      return false;
   }

   private static boolean isSignedFormat(int internalFormat) {
      return (
         internalFormat == GL43C.GL_R8I ||
         internalFormat == GL43C.GL_RG8I ||
         internalFormat == GL43C.GL_RGB8I ||
         internalFormat == GL43C.GL_RGBA8I ||
         internalFormat == GL43C.GL_R16I ||
         internalFormat == GL43C.GL_RG16I ||
         internalFormat == GL43C.GL_RGB16I ||
         internalFormat == GL43C.GL_RGBA16I ||
         internalFormat == GL43C.GL_R32I ||
         internalFormat == GL43C.GL_RG32I ||
         internalFormat == GL43C.GL_RGB32I ||
         internalFormat == GL43C.GL_RGBA32I
      );
   }

   private static int getComponentCount(int internalFormat) {
      String name = getFormatName(internalFormat);
      if (name.startsWith("RGBA") || name.startsWith("BGRA")) return 4;
      if (name.startsWith("RGB") || name.startsWith("BGR")) return 3;
      if (name.startsWith("RG")) return 2;
      if (name.startsWith("R") || name.startsWith("RED")) return 1;
      return 4;
   }

   public static JsonObject dumpTexture(int textureId, String outputPath) {
      JsonObject result = new JsonObject();

      int target = detectTarget(textureId);
      int previousTexture = GL43C.glGetInteger(getBindingQuery(target));
      try {
         GL43C.glBindTexture(target, textureId);

         int width = GL43C.glGetTexLevelParameteri(target, 0, GL43C.GL_TEXTURE_WIDTH);
         int height = GL43C.glGetTexLevelParameteri(target, 0, GL43C.GL_TEXTURE_HEIGHT);
         int depth = (target == GL43C.GL_TEXTURE_3D) ? GL43C.glGetTexLevelParameteri(target, 0, GL43C.GL_TEXTURE_DEPTH) : 1;

         if (width <= 0 || height <= 0) {
            result.addProperty("error", "Texture " + textureId + " has invalid dimensions: " + width + "x" + height);
            return result;
         }

         int internalFormat = GL43C.glGetTexLevelParameteri(target, 0, GL43C.GL_TEXTURE_INTERNAL_FORMAT);
         boolean isInteger = isIntegerFormat(internalFormat);

         result.addProperty("textureId", textureId);
         result.addProperty("width", width);
         result.addProperty("height", height);
         if (depth > 1) result.addProperty("depth", depth);
         result.addProperty("internalFormat", internalFormat);
         result.addProperty("formatName", getFormatName(internalFormat));

         if (target == GL43C.GL_TEXTURE_3D && depth > 1) {
            dump3DSlices(target, width, height, depth, internalFormat, isInteger, outputPath);
            result.addProperty("success", true);
            result.addProperty("path", outputPath.replace(".png", "_layer*.png"));
            result.addProperty("layers", depth);
         } else {
            dumpSlice(target, width, height, 0, 1, internalFormat, isInteger, outputPath);
            result.addProperty("success", true);
            result.addProperty("path", new File(outputPath).getAbsolutePath());
         }
      } catch (Exception e) {
         Viewfinder.LOGGER.error("Failed to dump texture {}", textureId, e);
         result.addProperty("error", e.getMessage());
      } finally {
         GL43C.glBindTexture(target, previousTexture);
      }

      return result;
   }

   public static JsonObject dumpTextureRaw(int textureId, String outputPath) {
      JsonObject result = new JsonObject();

      int target = detectTarget(textureId);
      int previousTexture = GL43C.glGetInteger(getBindingQuery(target));
      try {
         GL43C.glBindTexture(target, textureId);

         int width = GL43C.glGetTexLevelParameteri(target, 0, GL43C.GL_TEXTURE_WIDTH);
         int height = GL43C.glGetTexLevelParameteri(target, 0, GL43C.GL_TEXTURE_HEIGHT);
         int depth = (target == GL43C.GL_TEXTURE_3D) ? GL43C.glGetTexLevelParameteri(target, 0, GL43C.GL_TEXTURE_DEPTH) : 1;

         if (width <= 0 || height <= 0) {
            result.addProperty("error", "Texture " + textureId + " has invalid dimensions: " + width + "x" + height);
            return result;
         }

         int internalFormat = GL43C.glGetTexLevelParameteri(target, 0, GL43C.GL_TEXTURE_INTERNAL_FORMAT);
         boolean isInteger = isIntegerFormat(internalFormat);
         int components = getComponentCount(internalFormat);

         int pixelFormat, pixelType, bytesPerComponent;
         if (isInteger) {
            boolean signed = isSignedFormat(internalFormat);
            pixelFormat = (components == 1)
               ? GL43C.GL_RED_INTEGER
               : (components == 2)
                  ? GL43C.GL_RG_INTEGER
                  : (components == 3)
                     ? GL43C.GL_RGB_INTEGER
                     : GL43C.GL_RGBA_INTEGER;
            pixelType = signed ? GL43C.GL_INT : GL43C.GL_UNSIGNED_INT;
            bytesPerComponent = 4;
         } else {
            pixelFormat = (components == 1) ? GL43C.GL_RED : (components == 2) ? GL43C.GL_RG : (components == 3) ? GL43C.GL_RGB : GL43C.GL_RGBA;
            pixelType = GL43C.GL_FLOAT;
            bytesPerComponent = 4;
         }

         int totalPixels = width * height * depth;
         int bufferSize = totalPixels * components * bytesPerComponent;

         ByteBuffer buffer = MemoryUtil.memAlloc(bufferSize);
         try {
            GL43C.glGetTexImage(target, 0, pixelFormat, pixelType, buffer);

            File outputFile = new File(outputPath);
            outputFile.getParentFile().mkdirs();
            try (FileOutputStream fos = new FileOutputStream(outputFile); FileChannel channel = fos.getChannel()) {
               channel.write(buffer);
            }

            result.addProperty("success", true);
            result.addProperty("path", outputFile.getAbsolutePath());
            result.addProperty("width", width);
            result.addProperty("height", height);
            if (depth > 1) result.addProperty("depth", depth);
            result.addProperty("components", components);
            result.addProperty("bytesPerComponent", bytesPerComponent);
            result.addProperty("pixelType", isInteger ? (isSignedFormat(internalFormat) ? "int" : "uint") : "float");
            result.addProperty("totalBytes", bufferSize);
            result.addProperty("internalFormat", internalFormat);
            result.addProperty("formatName", getFormatName(internalFormat));
            result.addProperty("textureId", textureId);
         } finally {
            MemoryUtil.memFree(buffer);
         }
      } catch (Exception e) {
         Viewfinder.LOGGER.error("Failed to dump raw texture {}", textureId, e);
         result.addProperty("error", e.getMessage());
      } finally {
         GL43C.glBindTexture(target, previousTexture);
      }

      return result;
   }

   private static void dump3DSlices(int target, int width, int height, int depth, int internalFormat, boolean isInteger, String outputPath) throws Exception {
      int components = getComponentCount(internalFormat);

      if (isInteger) {
         boolean signed = isSignedFormat(internalFormat);
         IntBuffer buffer = MemoryUtil.memAllocInt(width * height * depth * 4);
         try {
            int pixelFormat = (components == 1)
               ? GL43C.GL_RED_INTEGER
               : (components == 2)
                  ? GL43C.GL_RG_INTEGER
                  : (components == 3)
                     ? GL43C.GL_RGB_INTEGER
                     : GL43C.GL_RGBA_INTEGER;
            int pixelType = signed ? GL43C.GL_INT : GL43C.GL_UNSIGNED_INT;
            GL43C.glGetTexImage(target, 0, pixelFormat, pixelType, buffer);

            for (int z = 0; z < depth; z++) {
               String layerPath = outputPath.replace(".png", "_layer" + z + ".png");
               writeIntegerSlice(buffer, width, height, z, components, signed, layerPath);
            }
         } finally {
            MemoryUtil.memFree(buffer);
         }
      } else {
         FloatBuffer buffer = MemoryUtil.memAllocFloat(width * height * depth * 4);
         try {
            GL43C.glGetTexImage(target, 0, GL43C.GL_RGBA, GL43C.GL_FLOAT, buffer);

            for (int z = 0; z < depth; z++) {
               String layerPath = outputPath.replace(".png", "_layer" + z + ".png");
               writeFloatSlice(buffer, width, height, z, layerPath);
            }
         } finally {
            MemoryUtil.memFree(buffer);
         }
      }
   }

   private static void dumpSlice(int target, int width, int height, int layer, int totalDepth, int internalFormat, boolean isInteger, String outputPath)
      throws Exception {
      int components = getComponentCount(internalFormat);

      if (isInteger) {
         dumpIntegerSlice(target, width, height, layer, totalDepth, internalFormat, components, outputPath);
      } else {
         dumpFloatSlice(target, width, height, layer, totalDepth, components, outputPath);
      }
   }

   private static void dumpFloatSlice(int target, int width, int height, int layer, int totalDepth, int components, String outputPath) throws Exception {
      int readHeight = (totalDepth > 1) ? height * totalDepth : height;
      FloatBuffer buffer = MemoryUtil.memAllocFloat(width * readHeight * 4);
      try {
         GL43C.glGetTexImage(target, 0, GL43C.GL_RGBA, GL43C.GL_FLOAT, buffer);

         BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
         int sliceOffset = layer * width * height * 4;
         for (int y = 0; y < height; y++) {
            int srcY = height - 1 - y;
            for (int x = 0; x < width; x++) {
               int i = sliceOffset + (srcY * width + x) * 4;
               int r = clamp((int) (buffer.get(i) * 255.0f));
               int g = clamp((int) (buffer.get(i + 1) * 255.0f));
               int b = clamp((int) (buffer.get(i + 2) * 255.0f));
               int a = clamp((int) (buffer.get(i + 3) * 255.0f));
               image.setRGB(x, y, (a << 24) | (r << 16) | (g << 8) | b);
            }
         }

         File outputFile = new File(outputPath);
         outputFile.getParentFile().mkdirs();
         ImageIO.write(image, "PNG", outputFile);
      } finally {
         MemoryUtil.memFree(buffer);
      }
   }

   private static void dumpIntegerSlice(int target, int width, int height, int layer, int totalDepth, int internalFormat, int components, String outputPath)
      throws Exception {
      boolean signed = isSignedFormat(internalFormat);
      int readHeight = (totalDepth > 1) ? height * totalDepth : height;
      IntBuffer buffer = MemoryUtil.memAllocInt(width * readHeight * 4);
      try {
         int pixelFormat = (components == 1)
            ? GL43C.GL_RED_INTEGER
            : (components == 2)
               ? GL43C.GL_RG_INTEGER
               : (components == 3)
                  ? GL43C.GL_RGB_INTEGER
                  : GL43C.GL_RGBA_INTEGER;
         int pixelType = signed ? GL43C.GL_INT : GL43C.GL_UNSIGNED_INT;

         GL43C.glGetTexImage(target, 0, pixelFormat, pixelType, buffer);

         BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
         int sliceOffset = layer * width * height * 4;

         // Find max value for normalization
         long maxVal = 0;
         for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
               int i = sliceOffset + (y * width + x) * 4;
               for (int c = 0; c < Math.min(components, 4); c++) {
                  long v = signed ? Math.abs((long) buffer.get(i + c)) : Integer.toUnsignedLong(buffer.get(i + c));
                  maxVal = Math.max(maxVal, v);
               }
            }
         }
         if (maxVal == 0) maxVal = 1;

         for (int y = 0; y < height; y++) {
            int srcY = height - 1 - y;
            for (int x = 0; x < width; x++) {
               int i = sliceOffset + (srcY * width + x) * 4;
               int r, g, b, a;

               if (signed) {
                  r = (int) ((Math.abs((long) buffer.get(i)) * 255L) / maxVal);
                  g = components >= 2 ? (int) ((Math.abs((long) buffer.get(i + 1)) * 255L) / maxVal) : 0;
                  b = components >= 3 ? (int) ((Math.abs((long) buffer.get(i + 2)) * 255L) / maxVal) : 0;
                  a = components >= 4 ? (int) ((Math.abs((long) buffer.get(i + 3)) * 255L) / maxVal) : 255;
               } else {
                  r = (int) ((Integer.toUnsignedLong(buffer.get(i)) * 255L) / maxVal);
                  g = components >= 2 ? (int) ((Integer.toUnsignedLong(buffer.get(i + 1)) * 255L) / maxVal) : 0;
                  b = components >= 3 ? (int) ((Integer.toUnsignedLong(buffer.get(i + 2)) * 255L) / maxVal) : 0;
                  a = components >= 4 ? (int) ((Integer.toUnsignedLong(buffer.get(i + 3)) * 255L) / maxVal) : 255;
               }

               image.setRGB(x, y, (clamp(a) << 24) | (clamp(r) << 16) | (clamp(g) << 8) | clamp(b));
            }
         }

         File outputFile = new File(outputPath);
         outputFile.getParentFile().mkdirs();
         ImageIO.write(image, "PNG", outputFile);
      } finally {
         MemoryUtil.memFree(buffer);
      }
   }

   private static void writeFloatSlice(FloatBuffer buffer, int width, int height, int layer, String outputPath) throws Exception {
      BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
      int sliceOffset = layer * width * height * 4;
      for (int y = 0; y < height; y++) {
         int srcY = height - 1 - y;
         for (int x = 0; x < width; x++) {
            int i = sliceOffset + (srcY * width + x) * 4;
            int r = clamp((int) (buffer.get(i) * 255.0f));
            int g = clamp((int) (buffer.get(i + 1) * 255.0f));
            int b = clamp((int) (buffer.get(i + 2) * 255.0f));
            int a = clamp((int) (buffer.get(i + 3) * 255.0f));
            image.setRGB(x, y, (a << 24) | (r << 16) | (g << 8) | b);
         }
      }
      File outputFile = new File(outputPath);
      outputFile.getParentFile().mkdirs();
      ImageIO.write(image, "PNG", outputFile);
   }

   private static void writeIntegerSlice(IntBuffer buffer, int width, int height, int layer, int components, boolean signed, String outputPath)
      throws Exception {
      BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
      int sliceOffset = layer * width * height * 4;

      long maxVal = 0;
      for (int y = 0; y < height; y++) {
         for (int x = 0; x < width; x++) {
            int i = sliceOffset + (y * width + x) * 4;
            for (int c = 0; c < Math.min(components, 4); c++) {
               long v = signed ? Math.abs((long) buffer.get(i + c)) : Integer.toUnsignedLong(buffer.get(i + c));
               maxVal = Math.max(maxVal, v);
            }
         }
      }
      if (maxVal == 0) maxVal = 1;

      for (int y = 0; y < height; y++) {
         int srcY = height - 1 - y;
         for (int x = 0; x < width; x++) {
            int i = sliceOffset + (srcY * width + x) * 4;
            int r, g, b, a;
            if (signed) {
               r = (int) ((Math.abs((long) buffer.get(i)) * 255L) / maxVal);
               g = components >= 2 ? (int) ((Math.abs((long) buffer.get(i + 1)) * 255L) / maxVal) : 0;
               b = components >= 3 ? (int) ((Math.abs((long) buffer.get(i + 2)) * 255L) / maxVal) : 0;
               a = components >= 4 ? (int) ((Math.abs((long) buffer.get(i + 3)) * 255L) / maxVal) : 255;
            } else {
               r = (int) ((Integer.toUnsignedLong(buffer.get(i)) * 255L) / maxVal);
               g = components >= 2 ? (int) ((Integer.toUnsignedLong(buffer.get(i + 1)) * 255L) / maxVal) : 0;
               b = components >= 3 ? (int) ((Integer.toUnsignedLong(buffer.get(i + 2)) * 255L) / maxVal) : 0;
               a = components >= 4 ? (int) ((Integer.toUnsignedLong(buffer.get(i + 3)) * 255L) / maxVal) : 255;
            }
            image.setRGB(x, y, (clamp(a) << 24) | (clamp(r) << 16) | (clamp(g) << 8) | clamp(b));
         }
      }
      File outputFile = new File(outputPath);
      outputFile.getParentFile().mkdirs();
      ImageIO.write(image, "PNG", outputFile);
   }

   private static int detectTarget(int textureId) {
      // Try GL_TEXTURE_2D first (most common)
      int prev2D = GL43C.glGetInteger(GL43C.GL_TEXTURE_BINDING_2D);
      GL43C.glBindTexture(GL43C.GL_TEXTURE_2D, textureId);
      int w = GL43C.glGetTexLevelParameteri(GL43C.GL_TEXTURE_2D, 0, GL43C.GL_TEXTURE_WIDTH);
      GL43C.glBindTexture(GL43C.GL_TEXTURE_2D, prev2D);
      if (w > 0) return GL43C.GL_TEXTURE_2D;

      // Try GL_TEXTURE_3D
      int prev3D = GL43C.glGetInteger(GL43C.GL_TEXTURE_BINDING_3D);
      GL43C.glBindTexture(GL43C.GL_TEXTURE_3D, textureId);
      w = GL43C.glGetTexLevelParameteri(GL43C.GL_TEXTURE_3D, 0, GL43C.GL_TEXTURE_WIDTH);
      GL43C.glBindTexture(GL43C.GL_TEXTURE_3D, prev3D);
      if (w > 0) return GL43C.GL_TEXTURE_3D;

      return GL43C.GL_TEXTURE_2D;
   }

   private static int getBindingQuery(int target) {
      if (target == GL43C.GL_TEXTURE_3D) return GL43C.GL_TEXTURE_BINDING_3D;
      return GL43C.GL_TEXTURE_BINDING_2D;
   }

   private static int clamp(int value) {
      return Math.max(0, Math.min(255, value));
   }

   private static String getFormatName(int glFormat) {
      return switch (glFormat) {
         case GL43C.GL_RGBA8 -> "RGBA8";
         case GL43C.GL_RGB8 -> "RGB8";
         case GL43C.GL_R8 -> "R8";
         case GL43C.GL_RG8 -> "RG8";
         case GL43C.GL_R16F -> "R16F";
         case GL43C.GL_RG16F -> "RG16F";
         case GL43C.GL_RGB16F -> "RGB16F";
         case GL43C.GL_RGBA16F -> "RGBA16F";
         case GL43C.GL_R32F -> "R32F";
         case GL43C.GL_RG32F -> "RG32F";
         case GL43C.GL_RGB32F -> "RGB32F";
         case GL43C.GL_RGBA32F -> "RGBA32F";
         case GL43C.GL_R8I -> "R8I";
         case GL43C.GL_RG8I -> "RG8I";
         case GL43C.GL_RGB8I -> "RGB8I";
         case GL43C.GL_RGBA8I -> "RGBA8I";
         case GL43C.GL_R8UI -> "R8UI";
         case GL43C.GL_RG8UI -> "RG8UI";
         case GL43C.GL_RGB8UI -> "RGB8UI";
         case GL43C.GL_RGBA8UI -> "RGBA8UI";
         case GL43C.GL_R16I -> "R16I";
         case GL43C.GL_RG16I -> "RG16I";
         case GL43C.GL_RGB16I -> "RGB16I";
         case GL43C.GL_RGBA16I -> "RGBA16I";
         case GL43C.GL_R16UI -> "R16UI";
         case GL43C.GL_RG16UI -> "RG16UI";
         case GL43C.GL_RGB16UI -> "RGB16UI";
         case GL43C.GL_RGBA16UI -> "RGBA16UI";
         case GL43C.GL_R32I -> "R32I";
         case GL43C.GL_RG32I -> "RG32I";
         case GL43C.GL_RGB32I -> "RGB32I";
         case GL43C.GL_RGBA32I -> "RGBA32I";
         case GL43C.GL_R32UI -> "R32UI";
         case GL43C.GL_RG32UI -> "RG32UI";
         case GL43C.GL_RGB32UI -> "RGB32UI";
         case GL43C.GL_RGBA32UI -> "RGBA32UI";
         case GL43C.GL_R16 -> "R16";
         case GL43C.GL_RG16 -> "RG16";
         case GL43C.GL_RGB16 -> "RGB16";
         case GL43C.GL_RGBA16 -> "RGBA16";
         case GL43C.GL_R11F_G11F_B10F -> "R11F_G11F_B10F";
         case GL43C.GL_RGB10_A2 -> "RGB10_A2";
         default -> "0x" + Integer.toHexString(glFormat);
      };
   }
}
