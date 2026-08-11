package dev.xirreal.viewfinder.commands;

import dev.xirreal.viewfinder.capture.ErrorCapture;
import java.io.File;
import java.net.URI;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;

final class ChatFeedback {

   private static final int MAX_INLINE_MESSAGE_LENGTH = 180;

   private ChatFeedback() {}

   static void send(FabricClientCommandSource source, Component component) {
      source.sendFeedback(component);
   }

   static void header(FabricClientCommandSource source, String title) {
      send(source, Component.empty().append(tag()).append(" ").append(Component.literal(title).withStyle(ChatFormatting.WHITE)));
   }

   static void success(FabricClientCommandSource source, String message) {
      message(source, message, ChatFormatting.GREEN);
   }

   static void warning(FabricClientCommandSource source, String message) {
      message(source, message, ChatFormatting.YELLOW);
   }

   static void error(FabricClientCommandSource source, String message) {
      message(source, message, ChatFormatting.RED);
   }

   static void info(FabricClientCommandSource source, String message) {
      message(source, message, ChatFormatting.GRAY);
   }

   static void row(FabricClientCommandSource source, String label, String value) {
      row(source, label, Component.literal(value).withStyle(ChatFormatting.GRAY));
   }

   static void row(FabricClientCommandSource source, String label, Component value) {
      send(source, rowComponent(label, value));
   }

   static void listItem(FabricClientCommandSource source, Component value) {
      send(source, Component.literal("  ").append(value));
   }

   static void commandHint(FabricClientCommandSource source, String command, String description) {
      send(
         source,
         Component.literal("  ")
            .append(suggestButton(command, command))
            .append(Component.literal("  " + description).withStyle(ChatFormatting.DARK_GRAY))
      );
   }

   static void pathRow(FabricClientCommandSource source, String label, String path) {
      row(source, label, pathValue(path));
   }

   static Component savedPath(String label, String path) {
      return Component.empty().append(tag()).append(" ").append(Component.literal(label + " ").withStyle(ChatFormatting.GRAY)).append(pathValue(path));
   }

   static MutableComponent pathValue(String path) {
      String display = displayPath(path);
      MutableComponent component = Component.literal(display)
         .withStyle(style ->
            style
               .withColor(ChatFormatting.AQUA)
               .withInsertion(path)
               .withHoverEvent(new HoverEvent.ShowText(Component.literal("Open path").withStyle(ChatFormatting.GRAY)))
         );

      if (path.indexOf('*') >= 0) {
         component.withStyle(style ->
            style
               .withClickEvent(new ClickEvent.CopyToClipboard(path))
               .withHoverEvent(new HoverEvent.ShowText(Component.literal("Copy path").withStyle(ChatFormatting.GRAY)))
         );
      } else {
         component.withStyle(style -> style.withClickEvent(new ClickEvent.OpenFile(path)));
      }

      return component.append(" ").append(copyButton("copy", path));
   }

   static MutableComponent urlValue(String label, String url) {
      URI uri = URI.create(url);
      return Component.literal(label)
         .withStyle(style ->
            style
               .withColor(ChatFormatting.AQUA)
               .withClickEvent(new ClickEvent.OpenUrl(uri))
               .withInsertion(url)
               .withHoverEvent(new HoverEvent.ShowText(Component.literal("Open URL").withStyle(ChatFormatting.GRAY)))
         )
         .append(" ")
         .append(copyButton("copy", url));
   }

   static MutableComponent copyButton(String label, String value) {
      return button(label, ChatFormatting.AQUA, new ClickEvent.CopyToClipboard(value), "Copy to clipboard");
   }

   static MutableComponent suggestButton(String label, String command) {
      return Component.literal(label)
         .withStyle(style ->
            style
               .withColor(ChatFormatting.AQUA)
               .withClickEvent(new ClickEvent.SuggestCommand(command))
               .withHoverEvent(new HoverEvent.ShowText(Component.literal("Insert command").withStyle(ChatFormatting.GRAY)))
         );
   }

   static void errorEntry(FabricClientCommandSource source, int index, ErrorCapture.ErrorEntry error) {
      String type = emptyToUnknown(error.type);
      String message = compact(error.message, MAX_INLINE_MESSAGE_LENGTH);
      MutableComponent line = Component.literal("  " + index + ". ").withStyle(ChatFormatting.DARK_GRAY)
         .append(Component.literal(type + "  ").withStyle(ChatFormatting.RED))
         .append(Component.literal(message).withStyle(ChatFormatting.GRAY))
         .append(Component.literal("  " + age(error.timestamp)).withStyle(ChatFormatting.DARK_GRAY))
         .append(" ")
         .append(copyButton("copy", errorDetails(error)));

      if (hasText(error.filename)) {
         line.append(" ").append(copyButton("file", error.filename));
      }

      send(source, line);

      String firstStackLine = firstMeaningfulStackLine(error.stackTrace);
      if (firstStackLine != null) {
         row(source, "Stack", Component.literal(compact(firstStackLine, MAX_INLINE_MESSAGE_LENGTH)).withStyle(ChatFormatting.GRAY));
      }
   }

   static String compact(String value, int maxLength) {
      if (!hasText(value)) {
         return "<empty>";
      }

      String normalized = value.replace('\r', ' ').replace('\n', ' ').replace('\t', ' ').trim();
      if (normalized.length() <= maxLength) {
         return normalized;
      }

      return normalized.substring(0, Math.max(0, maxLength - 3)) + "...";
   }

   static String formatBytes(long bytes) {
      if (bytes < 1024) {
         return bytes + " B";
      }

      double value = bytes;
      String[] units = { "KiB", "MiB", "GiB" };
      int unit = -1;
      do {
         value /= 1024.0;
         unit++;
      } while (value >= 1024.0 && unit < units.length - 1);

      return String.format(Locale.ROOT, "%.2f %s", value, units[unit]);
   }

   static String formatNanos(long nanos) {
      if (nanos < 1_000L) {
         return nanos + " ns";
      }
      if (nanos < 1_000_000L) {
         return String.format(Locale.ROOT, "%.2f us", nanos / 1_000.0);
      }
      if (nanos < 1_000_000_000L) {
         return String.format(Locale.ROOT, "%.2f ms", nanos / 1_000_000.0);
      }
      return String.format(Locale.ROOT, "%.2f s", nanos / 1_000_000_000.0);
   }

   static String exceptionMessage(Exception e) {
      return hasText(e.getMessage()) ? e.getMessage() : e.getClass().getSimpleName();
   }

   private static MutableComponent tag() {
      return Component.literal("[").withStyle(ChatFormatting.DARK_GRAY)
         .append(Component.literal("viewfinder").withStyle(ChatFormatting.AQUA))
         .append(Component.literal("]").withStyle(ChatFormatting.DARK_GRAY));
   }

   private static void message(FabricClientCommandSource source, String message, ChatFormatting color) {
      send(source, Component.empty().append(tag()).append(" ").append(Component.literal(message).withStyle(color)));
   }

   private static MutableComponent rowComponent(String label, Component value) {
      return Component.literal("  ")
         .append(Component.literal(label).withStyle(ChatFormatting.DARK_GRAY))
         .append(Component.literal("  ").withStyle(ChatFormatting.DARK_GRAY))
         .append(value);
   }

   private static MutableComponent button(String label, ChatFormatting color, ClickEvent clickEvent, String hoverText) {
      return Component.literal("[" + label + "]")
         .withStyle(style ->
            style
               .withColor(color)
               .withClickEvent(clickEvent)
               .withHoverEvent(new HoverEvent.ShowText(Component.literal(hoverText).withStyle(ChatFormatting.GRAY)))
         );
   }

   private static String displayPath(String path) {
      try {
         Path absolute = Path.of(path).toAbsolutePath().normalize();
         Path gameDir = Minecraft.getInstance().gameDirectory.toPath().toAbsolutePath().normalize();
         if (absolute.startsWith(gameDir)) {
            return "." + File.separator + gameDir.relativize(absolute);
         }
      } catch (Exception ignored) {}

      return path;
   }

   private static String age(long timestampMillis) {
      long elapsed = Math.max(0L, System.currentTimeMillis() - timestampMillis);
      long seconds = TimeUnit.MILLISECONDS.toSeconds(elapsed);
      if (seconds < 1L) {
         return "just now";
      }
      if (seconds < 60L) {
         return seconds + "s ago";
      }
      long minutes = TimeUnit.SECONDS.toMinutes(seconds);
      if (minutes < 60L) {
         return minutes + "m ago";
      }
      long hours = TimeUnit.MINUTES.toHours(minutes);
      if (hours < 24L) {
         return hours + "h ago";
      }
      long days = TimeUnit.HOURS.toDays(hours);
      return days + "d ago";
   }

   private static String errorDetails(ErrorCapture.ErrorEntry error) {
      StringBuilder builder = new StringBuilder();
      builder.append("Type: ").append(emptyToUnknown(error.type)).append('\n');
      if (hasText(error.filename)) {
         builder.append("File: ").append(error.filename).append('\n');
      }
      builder.append("Captured: ").append(age(error.timestamp)).append('\n');
      builder.append("Message: ").append(hasText(error.message) ? error.message : "<empty>");
      if (hasText(error.stackTrace)) {
         builder.append("\n\nStack trace:\n").append(error.stackTrace);
      }
      return builder.toString();
   }

   private static String firstMeaningfulStackLine(String stackTrace) {
      if (!hasText(stackTrace)) {
         return null;
      }

      for (String line : stackTrace.split("\\R")) {
         String trimmed = line.trim();
         if (trimmed.startsWith("at ")) {
            return trimmed;
         }
      }

      return null;
   }

   private static String emptyToUnknown(String value) {
      return hasText(value) ? value : "unknown";
   }

   private static boolean hasText(String value) {
      return value != null && !value.isBlank();
   }
}
