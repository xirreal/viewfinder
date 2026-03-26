package dev.xirreal.viewfinder.mixin.client;

import dev.xirreal.viewfinder.Viewfinder;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public class MixinGameRenderer {

   @Inject(method = "render", at = @At("TAIL"))
   private void viewfinder$onRenderTail(CallbackInfo ci) {
      dev.xirreal.viewfinder.ViewfinderClient.getScreenshotScheduler().tick();
   }
}
