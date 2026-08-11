package dev.xirreal.viewfinder.mixin.client;

import com.google.common.base.Throwables;
import dev.xirreal.viewfinder.Viewfinder;
import net.irisshaders.iris.gl.shader.ShaderCompileException;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "net.irisshaders.iris.Iris", remap = false)
public class MixinIrisErrors {
	@Inject(method = "handleException(Ljava/lang/Exception;)V", at = @At("HEAD"), remap = false)
	private static void viewfinder$captureException(Exception e, CallbackInfo ci) {
		String type = e.getClass().getSimpleName();
		String message = e.getMessage() != null ? e.getMessage() : "Unknown error";
		String filename = "";
		Throwable cause = e;
		while (cause != null) {
			if (cause instanceof ShaderCompileException compile) {
				filename = compile.getFilename();
				message = compile.getError();
				break;
			}
			cause = cause.getCause();
		}
		String stackTrace = Throwables.getStackTraceAsString(e);
		Viewfinder.getErrorCapture().addError(type, filename, message, stackTrace);
		Viewfinder.LOGGER.warn("Captured Iris error: [{}] {}", type, message);
	}
}
