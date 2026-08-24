package dev.xirreal.viewfinder.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.xirreal.viewfinder.capture.ProgramRegistry;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ViewfinderOperationsTest {
   @AfterEach
   void clearRegistry() {
      ProgramRegistry.clear();
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

   @Test
   void allActionsArePreflightedBeforeExecution() {
      Map<String, Object> arguments = Map.of("actions", java.util.List.of(
         Map.of("type", "clear_diagnostics"),
         Map.of("type", "inspect_program")
      ));

      assertThrows(IllegalArgumentException.class, () -> ViewfinderOperations.preflightActions(arguments));
   }
}
