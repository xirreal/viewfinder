package dev.xirreal.viewfinder.capture;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class CurrentPass {

   private static final ThreadLocal<Deque<String>> passStack = ThreadLocal.withInitial(ArrayDeque::new);
   private static final Map<String, Integer> observedPaths = new LinkedHashMap<>();

   public static void push(String name) {
      Deque<String> stack = passStack.get();
      stack.push(name);
      synchronized (observedPaths) {
         observedPaths.merge(path(stack), 1, Integer::sum);
      }
   }

   public static String pop() {
      Deque<String> passes = passStack.get();
      String pass = passes.poll();
      if (passes.isEmpty()) {
         passStack.remove();
      }
      return pass;
   }

   public static String getCurrentPass() {
      return passStack.get().peek();
   }

   public static String getCurrentPath() {
      return path(passStack.get());
   }

   public static JsonObject pipelineJson() {
      JsonObject root = new JsonObject();
      root.addProperty("currentPass", getCurrentPass());
      root.addProperty("currentPath", getCurrentPath());
      JsonArray paths = new JsonArray();
      synchronized (observedPaths) {
         observedPaths.forEach((path, observations) -> {
            JsonObject entry = new JsonObject();
            entry.addProperty("path", path);
            entry.addProperty("observations", observations);
            paths.add(entry);
         });
      }
      root.add("observedPasses", paths);
      return root;
   }

   public static void clear() {
      passStack.remove();
      synchronized (observedPaths) {
         observedPaths.clear();
      }
   }

   private static String path(Deque<String> stack) {
      List<String> names = new ArrayList<>(stack);
      java.util.Collections.reverse(names);
      return String.join("/", names);
   }
}
