package dev.xirreal.viewfinder.mixin.client;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(targets = "net.irisshaders.iris.gl.buffer.ShaderStorageBuffer", remap = false)
public interface ShaderStorageBufferAccessor {
   @Accessor("index")
   int getIndex();

   @Accessor("id")
   int getId();
}
