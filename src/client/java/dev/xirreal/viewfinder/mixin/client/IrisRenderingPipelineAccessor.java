package dev.xirreal.viewfinder.mixin.client;

import net.irisshaders.iris.pipeline.CustomTextureManager;
import net.irisshaders.iris.targets.RenderTargets;
import net.irisshaders.iris.shadows.ShadowRenderTargets;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(targets = "net.irisshaders.iris.pipeline.IrisRenderingPipeline", remap = false)
public interface IrisRenderingPipelineAccessor {
   @Accessor("renderTargets")
   RenderTargets getRenderTargets();

   @Accessor("customTextureManager")
   CustomTextureManager getCustomTextureManager();

   @Accessor("shadowRenderTargets")
   ShadowRenderTargets getShadowRenderTargets();
}
