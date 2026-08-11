package dev.xirreal.viewfinder;

import dev.xirreal.viewfinder.capture.ScreenshotScheduler;
import dev.xirreal.viewfinder.commands.ShaderDebugCommand;
import dev.xirreal.viewfinder.mcp.ViewfinderMcpServer;
import net.fabricmc.api.ClientModInitializer;

public class ViewfinderClient implements ClientModInitializer {

   private ViewfinderMcpServer mcpServer;
   private static final ScreenshotScheduler SCREENSHOT_SCHEDULER = new ScreenshotScheduler();

   public static ScreenshotScheduler getScreenshotScheduler() {
      return SCREENSHOT_SCHEDULER;
   }

   @Override
   public void onInitializeClient() {
      ShaderDebugCommand.register();

      try {
         mcpServer = new ViewfinderMcpServer();
         mcpServer.start();
         ViewfinderMcpServer server = mcpServer;
         Runtime.getRuntime().addShutdownHook(new Thread(() -> server.stop()));
      } catch (Exception e) {
         Viewfinder.LOGGER.error("Failed to start Viewfinder MCP server", e);
      }
   }
}
