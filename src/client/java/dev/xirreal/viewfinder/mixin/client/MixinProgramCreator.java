package dev.xirreal.viewfinder.mixin.client;

/*? if >=26.3 {*/
import com.mojang.renderpearl.backend.opengl.GlStateManager;
/*?} else {*/
/*import com.mojang.blaze3d.opengl.GlStateManager;
*//*?}*/
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL41C;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(targets = "net.irisshaders.iris.gl.shader.ProgramCreator", remap = false)
public class MixinProgramCreator {
   @Redirect(method = "create(Ljava/lang/String;[Lnet/irisshaders/iris/gl/shader/GlShader;)I", at = @At(value = "INVOKE",
      /*? if >=26.3 {*/
      target = "Lcom/mojang/renderpearl/backend/opengl/GlStateManager;glLinkProgram(I)V", remap = true))
      /*?} else {*/
      /*target = "Lcom/mojang/blaze3d/opengl/GlStateManager;glLinkProgram(I)V", remap = true))
      *//*?}*/
   private static void viewfinder$linkRetrievableProgram(int program) {
      GL41C.glProgramParameteri(program, GL41C.GL_PROGRAM_BINARY_RETRIEVABLE_HINT, GL20C.GL_TRUE);
      GlStateManager.glLinkProgram(program);
   }
}
