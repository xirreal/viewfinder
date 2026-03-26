package dev.xirreal.viewfinder;

import dev.xirreal.viewfinder.api.HttpServer;
import dev.xirreal.viewfinder.capture.ScreenshotScheduler;
import dev.xirreal.viewfinder.commands.ShaderDebugCommand;
import net.fabricmc.api.ClientModInitializer;

public class ViewfinderClient implements ClientModInitializer {

   private HttpServer httpServer;
   private static final ScreenshotScheduler SCREENSHOT_SCHEDULER = new ScreenshotScheduler();

   public static ScreenshotScheduler getScreenshotScheduler() {
      return SCREENSHOT_SCHEDULER;
   }

   @Override
   public void onInitializeClient() {
      ShaderDebugCommand.register();

      try {
         httpServer = new HttpServer();
         httpServer.start();
         HttpServer server = httpServer;
         Runtime.getRuntime().addShutdownHook(new Thread(() -> server.stop()));
      } catch (Exception e) {
         Viewfinder.LOGGER.error("Failed to start Viewfinder HTTP API", e);
      }
   }
}
