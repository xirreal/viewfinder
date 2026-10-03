package dev.xirreal.viewfinder.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.xirreal.viewfinder.capture.ProgramRegistry;
import java.util.Map;
import net.minecraft.client.OptionInstance;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ViewfinderOperationsTest {
   @AfterEach
   void clearRegistry() {
      ProgramRegistry.clear();
   }

   @Test
   void renderSettingsRespectClientOptionLimits() {
      var distance = new OptionInstance.IntRange(2, 16);
      var fov = new OptionInstance.IntRange(30, 110);
      assertDoesNotThrow(() -> ViewfinderOperations.validateRenderSetting("renderDistance", distance, 16));
      assertDoesNotThrow(() -> ViewfinderOperations.validateRenderSetting("fov", fov, 70));
      assertThrows(IllegalArgumentException.class, () ->
         ViewfinderOperations.validateRenderSetting("renderDistance", distance, 32));
      assertThrows(IllegalArgumentException.class, () ->
         ViewfinderOperations.validateRenderSetting("fov", fov, 111));
   }

   @Test
   void programSelectorRequiresExactlyOneNameOrId() {
      ProgramRegistry.register(17, "composite");

      assertEquals(17, ViewfinderOperations.programId(Map.of("name", "composite")));
      assertEquals(23, ViewfinderOperations.programId(Map.of("id", 23)));
      assertThrows(IllegalArgumentException.class, () -> ViewfinderOperations.programId(Map.of()));
      assertThrows(IllegalArgumentException.class, () -> ViewfinderOperations.programId(Map.of("id", 0)));
      assertThrows(IllegalArgumentException.class, () -> ViewfinderOperations.programId(Map.of("name", "composite", "id", 17)));
   }

   @Test
   void textureSelectorNeverDefaultsAndIsMutuallyExclusive() {
      assertEquals(23, ViewfinderOperations.textureId(Map.of("id", 23)));
      assertThrows(IllegalArgumentException.class, () -> ViewfinderOperations.textureId(Map.of()));
      assertThrows(IllegalArgumentException.class, () -> ViewfinderOperations.textureId(Map.of("id", 0)));
      assertThrows(IllegalArgumentException.class, () -> ViewfinderOperations.textureId(Map.of("name", "colortex0", "id", 23)));
   }

}
