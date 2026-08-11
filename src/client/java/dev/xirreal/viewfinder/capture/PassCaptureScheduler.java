package dev.xirreal.viewfinder.capture;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;

/** Captures the framebuffer attachments still bound when a named Iris debug group ends. */
public final class PassCaptureScheduler {
   private static volatile Request pending;

   private PassCaptureScheduler() {}

   public static synchronized CompletableFuture<JsonObject> schedule(String pass) {
      if (pending != null && !pending.result.isDone()) throw new IllegalStateException("A pass capture is already pending");
      if (pass == null || pass.isBlank()) throw new IllegalArgumentException("pass is required");
      Request request = new Request(pass, new CompletableFuture<>());
      pending = request;
      return request.result;
   }

   public static void onPassEnd() {
      Request request = pending;
      if (request == null) return;
      if (request.result.isDone()) {
         pending = null;
         return;
      }
      String path = CurrentPass.getCurrentPath();
      String leaf = CurrentPass.getCurrentPass();
      if (!request.pass.equals(path) && !request.pass.equals(leaf)) return;

      pending = null;
      try {
         request.result.complete(capture(path));
      } catch (Exception e) {
         request.result.completeExceptionally(e);
      }
   }

   private static JsonObject capture(String pass) throws Exception {
      Path directory = CaptureStore.create("pass-output");
      JsonObject result = new JsonObject();
      result.addProperty("captureId", directory.getFileName().toString());
      result.addProperty("pass", pass);
      result.addProperty("framebuffer", GL11C.glGetInteger(GL30C.GL_DRAW_FRAMEBUFFER_BINDING));
      JsonArray outputs = new JsonArray();

      int max = GL11C.glGetInteger(GL20C.GL_MAX_DRAW_BUFFERS);
      for (int index = 0; index < max; index++) {
         int attachment = GL11C.glGetInteger(GL20C.GL_DRAW_BUFFER0 + index);
         if (attachment == GL11C.GL_NONE) continue;
         captureAttachment(directory, outputs, attachment, "color" + index);
      }
      captureAttachment(directory, outputs, GL30C.GL_DEPTH_ATTACHMENT, "depth");
      result.add("outputs", outputs);
      return result;
   }

   private static void captureAttachment(Path directory, JsonArray outputs, int attachment, String name) {
      int type = GL30C.glGetFramebufferAttachmentParameteri(GL30C.GL_DRAW_FRAMEBUFFER, attachment,
         GL30C.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE);
      if (type != GL11C.GL_TEXTURE) return;
      int texture = GL30C.glGetFramebufferAttachmentParameteri(GL30C.GL_DRAW_FRAMEBUFFER, attachment,
         GL30C.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
      Path output = directory.resolve(name + ".png");
      JsonObject dump = TextureDumper.dumpTexture(texture, output.toString());
      dump.addProperty("attachment", name);
      dump.addProperty("resourceUri", "viewfinder://capture/" + directory.getFileName() + "/" + output.getFileName());
      outputs.add(dump);
   }

   private record Request(String pass, CompletableFuture<JsonObject> result) {}
}
