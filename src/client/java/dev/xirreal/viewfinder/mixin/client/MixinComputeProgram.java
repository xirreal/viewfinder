package dev.xirreal.viewfinder.mixin.client;

import dev.xirreal.viewfinder.Viewfinder;
import dev.xirreal.viewfinder.capture.CurrentPass;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "net.irisshaders.iris.gl.program.ComputeProgram", remap = false)
public class MixinComputeProgram {

   @Inject(method = "dispatch", at = @At("HEAD"), remap = false)
   private void viewfinder$beginDispatch(float width, float height, CallbackInfo ci) {
      String pass = CurrentPass.getCurrentPass();
      if (pass != null) {
         Viewfinder.getMetricsCollector().beginTiming(pass + "_compute");
      }
   }

   @Inject(method = "dispatch", at = @At("RETURN"), remap = false)
   private void viewfinder$endDispatch(float width, float height, CallbackInfo ci) {
      String pass = CurrentPass.getCurrentPass();
      if (pass != null) {
         Viewfinder.getMetricsCollector().endTiming(pass + "_compute");
      }
   }
}
