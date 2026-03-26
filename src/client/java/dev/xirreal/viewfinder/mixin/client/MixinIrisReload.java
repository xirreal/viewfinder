package dev.xirreal.viewfinder.mixin.client;

import dev.xirreal.viewfinder.Viewfinder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "net.irisshaders.iris.Iris", remap = false)
public class MixinIrisReload {
	@Inject(method = "reload", at = @At("HEAD"), remap = false)
	private static void viewfinder$onReloadStart(CallbackInfo ci) {
		Viewfinder.LOGGER.info("Iris shader reload started");
		Viewfinder.getErrorCapture().clearErrors();
	}

	@Inject(method = "reload", at = @At("RETURN"), remap = false)
	private static void viewfinder$onReloadEnd(CallbackInfo ci) {
		Viewfinder.LOGGER.info("Iris shader reload completed");
	}
}
