package dev.xirreal.viewfinder.api;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

public class JsonResponses {

   private static final Gson GSON = new Gson();

   public static String success() {
      JsonObject obj = new JsonObject();
      obj.addProperty("success", true);
      return GSON.toJson(obj);
   }

   public static String error(String message) {
      JsonObject obj = new JsonObject();
      obj.addProperty("success", false);
      obj.addProperty("error", message);
      return GSON.toJson(obj);
   }

   public static String statusResponse(boolean irisLoaded, String shaderpack) {
      JsonObject obj = new JsonObject();
      obj.addProperty("status", "ok");
      obj.addProperty("pack_loaded", irisLoaded);
      obj.addProperty("shaderpack", shaderpack);
      return GSON.toJson(obj);
   }

   public static String toJson(JsonObject obj) {
      return GSON.toJson(obj);
   }

   public static String toJson(JsonArray arr) {
      return GSON.toJson(arr);
   }
}
