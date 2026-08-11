package dev.xirreal.viewfinder.capture;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ShaderLogParser {
   private static final Pattern COLON = Pattern.compile("^(ERROR|WARNING):\\s*([^:]+):(\\d+):?\\s*(.*)$", Pattern.CASE_INSENSITIVE);
   private static final Pattern PAREN = Pattern.compile("^([^()]+)\\((\\d+)\\)\\s*:\\s*(error|warning)?\\s*(.*)$", Pattern.CASE_INSENSITIVE);

   private ShaderLogParser() {}

   public static List<Message> parse(String log) {
      List<Message> messages = new ArrayList<>();
      if (log == null) return messages;
      for (String line : log.lines().toList()) {
         Matcher colon = COLON.matcher(line.trim());
         if (colon.matches()) {
            messages.add(new Message(colon.group(1).toLowerCase(), colon.group(2), Integer.parseInt(colon.group(3)), colon.group(4)));
            continue;
         }
         Matcher paren = PAREN.matcher(line.trim());
         if (paren.matches()) {
            String severity = paren.group(3) == null ? "error" : paren.group(3).toLowerCase();
            messages.add(new Message(severity, paren.group(1), Integer.parseInt(paren.group(2)), paren.group(4)));
         }
      }
      return messages;
   }

   public record Message(String severity, String file, int line, String message) {}
}
