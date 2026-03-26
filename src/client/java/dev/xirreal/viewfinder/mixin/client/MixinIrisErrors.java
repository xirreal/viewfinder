package dev.xirreal.viewfinder.mixin.client;

import com.google.common.base.Throwables;
import dev.xirreal.viewfinder.Viewfinder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "net.irisshaders.iris.Iris", remap = false)
public class MixinIrisErrors {
	@Inject(method = "handleException", at = @At("HEAD"), remap = false)
	private static void viewfinder$captureException(Exception e, CallbackInfo ci) {
		String type = e.getClass().getSimpleName();
		String message = e.getMessage() != null ? e.getMessage() : "Unknown error";
		String stackTrace = Throwables.getStackTraceAsString(e);
		Viewfinder.getErrorCapture().addError(type, "", message, stackTrace);
		Viewfinder.LOGGER.warn("Captured Iris error: [{}] {}", type, message);
	}
}
