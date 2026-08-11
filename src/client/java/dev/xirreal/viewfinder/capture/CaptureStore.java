package dev.xirreal.viewfinder.capture;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.UUID;
import java.util.stream.Stream;
import net.minecraft.client.Minecraft;

public final class CaptureStore {
   private static final int MAX_CAPTURES = 20;

   private CaptureStore() {}

   public static Path create(String kind) throws IOException {
      Path root = root();
      Files.createDirectories(root);
      String id = Instant.now().toString().replace(':', '-') + "-" + UUID.randomUUID().toString().substring(0, 8);
      Path capture = root.resolve(id);
      Files.createDirectory(capture);
      Files.writeString(capture.resolve("kind.txt"), kind);
      trim(root);
      return capture;
   }

   public static Path resolve(String captureId, String file) {
      Path capture = SafePaths.resolve(root(), captureId);
      Path resolved = SafePaths.resolve(capture, file);
      try {
         if (!Files.isRegularFile(resolved) || !resolved.toRealPath().startsWith(capture.toRealPath())) {
            throw new IllegalArgumentException("Capture file not found");
         }
      } catch (IOException e) {
         throw new IllegalArgumentException("Capture file not found", e);
      }
      return resolved;
   }

   public static Path root() {
      return Minecraft.getInstance().gameDirectory.toPath().resolve("viewfinder_captures").toAbsolutePath().normalize();
   }

   private static void trim(Path root) throws IOException {
      try (Stream<Path> entries = Files.list(root)) {
         Path[] captures = entries.filter(Files::isDirectory)
            .filter(path -> Files.isRegularFile(path.resolve("kind.txt")))
            .sorted(Comparator.comparingLong(CaptureStore::modified).reversed())
            .toArray(Path[]::new);
         for (int i = MAX_CAPTURES; i < captures.length; i++) deleteTree(captures[i]);
      }
   }

   private static long modified(Path path) {
      try {
         return Files.getLastModifiedTime(path).toMillis();
      } catch (IOException ignored) {
         return 0;
      }
   }

   private static void deleteTree(Path root) throws IOException {
      try (Stream<Path> paths = Files.walk(root)) {
         for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
      }
   }
}
