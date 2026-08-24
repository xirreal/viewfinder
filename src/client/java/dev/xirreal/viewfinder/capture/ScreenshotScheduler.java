package dev.xirreal.viewfinder.capture;

import dev.xirreal.viewfinder.Viewfinder;
import dev.xirreal.viewfinder.compat.MinecraftCompat;
import java.io.File;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;

public class ScreenshotScheduler {

   private volatile int framesRemaining = -1;
   private volatile Consumer<String> callback;
   private int waitFramesRemaining;
   private CompletableFuture<Void> frameWait;
   private String lastScreenshotPath;

   public void scheduleScreenshot(int frames) {
      scheduleScreenshot(frames, null);
   }

   public void scheduleScreenshot(int frames, Consumer<String> callback) {
      this.framesRemaining = frames;
      this.callback = callback;
   }

   public synchronized void tick() {
      if (frameWait != null && --waitFramesRemaining == 0) {
         CompletableFuture<Void> completed = frameWait;
         frameWait = null;
         completed.complete(null);
      }

      if (framesRemaining < 0) {
         return;
      }

      if (framesRemaining == 0) {
         framesRemaining = -1;
         takeScreenshot();
         return;
      }

      framesRemaining--;
   }

   public boolean isActive() {
      return framesRemaining >= 0;
   }

   public synchronized CompletableFuture<Void> waitForFrames(int frames) {
      if (frames < 1) throw new IllegalArgumentException("frames must be positive");
      if (frameWait != null) throw new IllegalStateException("A frame wait is already active");
      waitFramesRemaining = frames;
      frameWait = new CompletableFuture<>();
      return frameWait;
   }

   public synchronized void cancelFrameWait(CompletableFuture<Void> wait) {
      if (frameWait != wait) return;
      frameWait = null;
      wait.cancel(false);
   }

   public String getLastScreenshotPath() {
      return lastScreenshotPath;
   }

   private void takeScreenshot() {
      Minecraft mc = Minecraft.getInstance();
      Consumer<String> cb = this.callback;
      this.callback = null;

      File screenshotsDir = new File(mc.gameDirectory, "screenshots");
      screenshotsDir.mkdirs();

      File file = Screenshot.getFile(screenshotsDir);

      Screenshot.grab(mc.gameDirectory, MinecraftCompat.mainRenderTarget(mc), component -> {
         String path = file.getAbsolutePath();
         lastScreenshotPath = path;

         Viewfinder.LOGGER.info("Screenshot saved: {}", path);

         if (cb != null) {
            mc.execute(() -> cb.accept(path));
         }
      });
   }
}
