package dev.xirreal.viewfinder.mixin.client;

import java.util.List;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(targets = "net.irisshaders.iris.gl.buffer.ShaderStorageBufferHolder", remap = false)
public interface ShaderStorageBufferHolderAccessor {
   @Accessor("ACTIVE_BUFFERS")
   static List<?> getActiveBuffers() {
      throw new AssertionError();
   }
}
