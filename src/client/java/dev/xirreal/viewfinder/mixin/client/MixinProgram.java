package dev.xirreal.viewfinder.mixin.client;

import dev.xirreal.viewfinder.capture.ProgramRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = {
   "net.irisshaders.iris.gl.program.Program",
   "net.irisshaders.iris.gl.program.ComputeProgram"
}, remap = false)
public abstract class MixinProgram {
   @Shadow(remap = false)
   public abstract int getProgramId();

   @Inject(method = "destroyInternal()V", at = @At("HEAD"), remap = false)
   private void viewfinder$forgetProgram(CallbackInfo ci) {
      ProgramRegistry.remove(getProgramId());
   }
}
