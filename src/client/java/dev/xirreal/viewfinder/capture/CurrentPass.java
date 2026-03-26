package dev.xirreal.viewfinder.capture;

public class CurrentPass {

   private static final ThreadLocal<String> currentPass = new ThreadLocal<>();

   public static void setPass(String name) {
      currentPass.set(name);
   }

   public static void clear() {
      currentPass.remove();
   }

   public static String getCurrentPass() {
      return currentPass.get();
   }
}
