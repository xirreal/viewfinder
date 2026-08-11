package dev.xirreal.viewfinder.mixin.client;

import dev.xirreal.viewfinder.Viewfinder;
import dev.xirreal.viewfinder.capture.CurrentPass;
import dev.xirreal.viewfinder.capture.ProgramRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "net.irisshaders.iris.Iris", remap = false)
public class MixinIrisReload {
	@Inject(method = "reload()V", at = @At("HEAD"), remap = false)
	private static void viewfinder$onReloadStart(CallbackInfo ci) {
		Viewfinder.LOGGER.info("Iris shader reload started");
		Viewfinder.getErrorCapture().clearErrors();
		Viewfinder.getMetricsCollector().reset();
      CurrentPass.clear();
      ProgramRegistry.clear();
	}

	@Inject(method = "reload()V", at = @At("RETURN"), remap = false)
	private static void viewfinder$onReloadEnd(CallbackInfo ci) {
		Viewfinder.LOGGER.info("Iris shader reload completed");
	}
}
