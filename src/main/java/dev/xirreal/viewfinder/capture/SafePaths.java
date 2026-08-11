package dev.xirreal.viewfinder.capture;

import java.nio.file.Path;

public final class SafePaths {
   private SafePaths() {}

   public static Path resolve(Path root, String relative) {
      if (relative == null || relative.isBlank() || Path.of(relative).isAbsolute()) {
         throw new IllegalArgumentException("Path must be relative");
      }
      Path normalizedRoot = root.toAbsolutePath().normalize();
      Path result = normalizedRoot.resolve(relative).normalize();
      if (!result.startsWith(normalizedRoot)) throw new IllegalArgumentException("Path escapes its allowed root");
      return result;
   }
}
