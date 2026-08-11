package dev.xirreal.viewfinder.mixin.client;

import dev.xirreal.viewfinder.Viewfinder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "net.irisshaders.iris.Iris", remap = false)
public class MixinCreatePipeline {
	@Inject(method = "createPipeline(Lnet/irisshaders/iris/shaderpack/materialmap/NamespacedId;)Lnet/irisshaders/iris/pipeline/WorldRenderingPipeline;", at = @At("RETURN"), remap = false)
	private static void viewfinder$onCreatePipeline(CallbackInfoReturnable<?> cir) {
		Object pipeline = cir.getReturnValue();
		if (pipeline != null && pipeline.getClass().getSimpleName().equals("VanillaRenderingPipeline")) {
			Viewfinder.LOGGER.warn("Iris fell back to VanillaRenderingPipeline, shader compilation likely failed");
		}
	}
}
