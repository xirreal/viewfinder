package dev.xirreal.viewfinder.mixin.client;

import dev.xirreal.viewfinder.Viewfinder;
import dev.xirreal.viewfinder.capture.CurrentPass;
import dev.xirreal.viewfinder.capture.PassCaptureScheduler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "net.irisshaders.iris.gl.GLDebug", remap = false)
public class MixinGLDebug {

   @Inject(method = "pushGroup(ILjava/lang/String;)V", at = @At("HEAD"), remap = false)
   private static void viewfinder$captureGroup(int id, String name, CallbackInfo ci) {
      CurrentPass.push(name);
      Viewfinder.getMetricsCollector().beginTiming(CurrentPass.getCurrentPath());
   }

   @Inject(method = "popGroup()V", at = @At("HEAD"), remap = false)
   private static void viewfinder$clearGroup(CallbackInfo ci) {
      PassCaptureScheduler.onPassEnd();
      Viewfinder.getMetricsCollector().endTiming();
      CurrentPass.pop();
   }
}
