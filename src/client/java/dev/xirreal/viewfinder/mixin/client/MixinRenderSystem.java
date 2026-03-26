package dev.xirreal.viewfinder.mixin.client;

import com.mojang.blaze3d.opengl.GlStateManager;
import dev.xirreal.viewfinder.Viewfinder;
import dev.xirreal.viewfinder.capture.CurrentPass;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GlStateManager.class)
public class MixinRenderSystem {

   @Inject(method = "_drawElements", at = @At("HEAD"))
   private static void viewfinder$beginDraw(int mode, int count, int type, long indices, CallbackInfo ci) {
      String pass = CurrentPass.getCurrentPass();
      if (pass != null) {
         Viewfinder.getMetricsCollector().beginTiming(pass + "_draw");
      }
   }

   @Inject(method = "_drawElements", at = @At("RETURN"))
   private static void viewfinder$endDraw(int mode, int count, int type, long indices, CallbackInfo ci) {
      String pass = CurrentPass.getCurrentPass();
      if (pass != null) {
         Viewfinder.getMetricsCollector().endTiming(pass + "_draw");
      }
   }
}
