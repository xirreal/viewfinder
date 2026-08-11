package dev.xirreal.viewfinder.capture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class SafePathsTest {
   @Test
   void resolvesInsideRoot() {
      Path root = Path.of("/tmp/viewfinder-test");
      assertEquals(root.resolve("capture/output.png"), SafePaths.resolve(root, "capture/output.png"));
   }

   @Test
   void rejectsTraversalAndAbsolutePaths() {
      Path root = Path.of("/tmp/viewfinder-test");
      assertThrows(IllegalArgumentException.class, () -> SafePaths.resolve(root, "../outside"));
      assertThrows(IllegalArgumentException.class, () -> SafePaths.resolve(root, "/outside"));
   }
}
