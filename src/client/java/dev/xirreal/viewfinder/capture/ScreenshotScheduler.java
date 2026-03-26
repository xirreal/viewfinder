package dev.xirreal.viewfinder.capture;

import dev.xirreal.viewfinder.Viewfinder;
import java.io.File;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;

public class ScreenshotScheduler {

   private volatile int framesRemaining = -1;
   private volatile Consumer<String> callback;
   private String lastScreenshotPath;

   public void scheduleScreenshot(int frames) {
      scheduleScreenshot(frames, null);
   }

   public void scheduleScreenshot(int frames, Consumer<String> callback) {
      this.framesRemaining = frames;
      this.callback = callback;
   }

   public void tick() {
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

      Screenshot.grab(mc.gameDirectory, mc.getMainRenderTarget(), component -> {
         String path = file.getAbsolutePath();
         lastScreenshotPath = path;

         Viewfinder.LOGGER.info("Screenshot saved: {}", path);

         if (cb != null) {
            mc.execute(() -> cb.accept(path));
         }
      });
   }
}
