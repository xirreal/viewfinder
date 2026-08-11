package dev.xirreal.viewfinder.compat;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

public final class MinecraftCompat {

   private MinecraftCompat() {}

   public static RenderTarget mainRenderTarget(Minecraft minecraft) {
      /*? if >=26.2 {*/
      return minecraft.gameRenderer.mainRenderTarget();
      /*?} else {*/
      /*return minecraft.getMainRenderTarget();
      *//*?}*/
   }

   public static Screen screen(Minecraft minecraft) {
      /*? if >=26.2 {*/
      return minecraft.gui.screen();
      /*?} else {*/
      /*return minecraft.screen;
      *//*?}*/
   }

   public static void setScreen(Minecraft minecraft, Screen screen) {
      /*? if >=26.2 {*/
      minecraft.gui.setScreen(screen);
      /*?} else {*/
      /*minecraft.setScreen(screen);
      *//*?}*/
   }

   public static String versionType() {
      return SharedConstants.getCurrentVersion().stable() ? "release" : "snapshot";
   }
}
