package dev.xirreal.viewfinder.mixin.client;

import com.google.common.collect.ImmutableSet;
import dev.xirreal.viewfinder.capture.ProgramRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "net.irisshaders.iris.gl.program.ProgramBuilder", remap = false)
public class MixinProgramBuilder {
   @Inject(method = "<init>(Ljava/lang/String;ILcom/google/common/collect/ImmutableSet;)V", at = @At("RETURN"), remap = false)
   private void viewfinder$rememberProgram(String name, int program, ImmutableSet<Integer> reservedTextureUnits, CallbackInfo ci) {
      ProgramRegistry.register(program, name);
   }
}
