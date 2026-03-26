package dev.xirreal.viewfinder;

import dev.xirreal.viewfinder.capture.ErrorCapture;
import dev.xirreal.viewfinder.capture.MetricsCollector;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Viewfinder implements ModInitializer {

   public static final String MOD_ID = "viewfinder";
   public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
   public static final String VERSION = FabricLoader.getInstance()
      .getModContainer("viewfinder")
      .map(c -> c.getMetadata().getVersion().getFriendlyString())
      .orElse("unknown");

   private static final ErrorCapture ERROR_CAPTURE = new ErrorCapture();
   private static final MetricsCollector METRICS_COLLECTOR = new MetricsCollector();

   public static ErrorCapture getErrorCapture() {
      return ERROR_CAPTURE;
   }

   public static MetricsCollector getMetricsCollector() {
      return METRICS_COLLECTOR;
   }

   @Override
   public void onInitialize() {
      LOGGER.info("Viewfinder loaded");
   }
}
