package dev.xirreal.viewfinder.mixin.client;

import dev.xirreal.viewfinder.capture.CurrentPass;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "net.irisshaders.iris.gl.GLDebug", remap = false)
public class MixinGLDebug {

   @Inject(method = "pushGroup", at = @At("HEAD"), remap = false)
   private static void viewfinder$captureGroup(int id, String name, CallbackInfo ci) {
      CurrentPass.setPass(name);
   }

   @Inject(method = "popGroup", at = @At("HEAD"), remap = false)
   private static void viewfinder$clearGroup(CallbackInfo ci) {
      CurrentPass.clear();
   }
}
