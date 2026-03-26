package dev.xirreal.viewfinder.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import dev.xirreal.viewfinder.Viewfinder;
import dev.xirreal.viewfinder.capture.SSBODumper;
import dev.xirreal.viewfinder.capture.SSBOResolver;
import dev.xirreal.viewfinder.capture.TextureDumper;
import dev.xirreal.viewfinder.capture.TextureResolver;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import net.irisshaders.iris.Iris;
import net.minecraft.client.Minecraft;

public class HttpServer {

   private static final int PORT = 7150;
   private com.sun.net.httpserver.HttpServer server;

   public void start() throws IOException {
      server = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("localhost", PORT), 0);
      server.setExecutor(Executors.newSingleThreadExecutor());

      server.createContext("/", new RootHandler());
      server.createContext("/status", new StatusHandler());
      server.createContext("/reload", new ReloadHandler());
      server.createContext("/errors", new ErrorsHandler());
      server.createContext("/screenshot/result", new ScreenshotResultHandler());
      server.createContext("/screenshot", new ScreenshotHandler());
      server.createContext("/metrics", new MetricsHandler());
      server.createContext("/ssbo", new SsboHandler());
      server.createContext("/list-ssbo", new SsboListHandler());
      server.createContext("/patched_shaders", new PatchedShadersHandler());
      server.createContext("/list-textures", new TexturesListHandler());
      server.createContext("/texture", new TextureHandler());

      server.start();
      Viewfinder.LOGGER.info("Viewfinder HTTP API started on localhost:{}", PORT);
   }

   public void stop() {
      if (server != null) {
         server.stop(0);
      }
   }

   private static Map<String, String> parseQueryParams(URI uri) {
      Map<String, String> params = new HashMap<>();
      String query = uri.getQuery();
      if (query == null || query.isEmpty()) return params;
      for (String param : query.split("&")) {
         String[] pair = param.split("=", 2);
         params.put(pair[0], pair.length == 2 ? pair[1] : "");
      }
      return params;
   }

   private static void sendJson(HttpExchange exchange, int statusCode, String json) throws IOException {
      byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "application/json");
      exchange.sendResponseHeaders(statusCode, bytes.length);
      try (OutputStream os = exchange.getResponseBody()) {
         os.write(bytes);
      }
   }

   private static void sendRenderedJson(HttpExchange exchange, String json) throws IOException {
      int statusCode = json.contains("\"error\"") ? 500 : 200;
      sendJson(exchange, statusCode, json);
   }

   // GET /status
   private static class StatusHandler implements HttpHandler {

      @Override
      public void handle(HttpExchange exchange) throws IOException {
         if (!"GET".equals(exchange.getRequestMethod())) {
            sendJson(exchange, 405, JsonResponses.error("Method not allowed"));
            return;
         }
         try {
            CompletableFuture<String> future = new CompletableFuture<>();
            Minecraft.getInstance().execute(() -> {
               try {
                  String shaderpack = Iris.getCurrentPackName();
                  future.complete(JsonResponses.statusResponse(true, shaderpack));
               } catch (Exception e) {
                  future.complete(JsonResponses.statusResponse(false, "unknown"));
               }
            });
            sendRenderedJson(exchange, future.get(5, TimeUnit.SECONDS));
         } catch (Exception e) {
            sendJson(exchange, 500, JsonResponses.error(e.getMessage()));
         }
      }
   }

   // POST /reload
   private static class ReloadHandler implements HttpHandler {

      @Override
      public void handle(HttpExchange exchange) throws IOException {
         if (!"POST".equals(exchange.getRequestMethod())) {
            sendJson(exchange, 405, JsonResponses.error("Method not allowed"));
            return;
         }
         try {
            CompletableFuture<String> future = new CompletableFuture<>();
            Minecraft.getInstance().execute(() -> {
               try {
                  Iris.reload();
                  JsonObject obj = new JsonObject();
                  obj.addProperty("success", true);
                  obj.add("errors", Viewfinder.getErrorCapture().toJson());
                  future.complete(JsonResponses.toJson(obj));
               } catch (Exception e) {
                  JsonObject obj = new JsonObject();
                  obj.addProperty("success", false);
                  obj.add("errors", Viewfinder.getErrorCapture().toJson());
                  future.complete(JsonResponses.toJson(obj));
               }
            });
            sendRenderedJson(exchange, future.get(30, TimeUnit.SECONDS));
         } catch (Exception e) {
            sendJson(exchange, 500, JsonResponses.error(e.getMessage()));
         }
      }
   }

   // GET /errors
   private static class ErrorsHandler implements HttpHandler {

      @Override
      public void handle(HttpExchange exchange) throws IOException {
         if (!"GET".equals(exchange.getRequestMethod())) {
            sendJson(exchange, 405, JsonResponses.error("Method not allowed"));
            return;
         }
         JsonObject obj = new JsonObject();
         obj.add("errors", Viewfinder.getErrorCapture().toJson());
         sendJson(exchange, 200, JsonResponses.toJson(obj));
      }
   }

   // POST /screenshot
   private static class ScreenshotHandler implements HttpHandler {

      @Override
      public void handle(HttpExchange exchange) throws IOException {
         if (!"POST".equals(exchange.getRequestMethod())) {
            sendJson(exchange, 405, JsonResponses.error("Method not allowed"));
            return;
         }
         Map<String, String> params = parseQueryParams(exchange.getRequestURI());
         int frames = 1;
         try {
            frames = Integer.parseInt(params.getOrDefault("frames", "1"));
         } catch (NumberFormatException ignored) {}

         final int f = frames;
         Minecraft.getInstance().execute(() -> dev.xirreal.viewfinder.ViewfinderClient.getScreenshotScheduler().scheduleScreenshot(f));

         JsonObject obj = new JsonObject();
         obj.addProperty("scheduled", true);
         obj.addProperty("frames", f);
         sendJson(exchange, 200, JsonResponses.toJson(obj));
      }
   }

   // GET /screenshot/result
   private static class ScreenshotResultHandler implements HttpHandler {

      @Override
      public void handle(HttpExchange exchange) throws IOException {
         if (!"GET".equals(exchange.getRequestMethod())) {
            sendJson(exchange, 405, JsonResponses.error("Method not allowed"));
            return;
         }
         JsonObject obj = new JsonObject();
         String path = dev.xirreal.viewfinder.ViewfinderClient.getScreenshotScheduler().getLastScreenshotPath();
         if (path != null) {
            obj.addProperty("path", path);
         } else {
            obj.add("path", null);
         }
         sendJson(exchange, 200, JsonResponses.toJson(obj));
      }
   }

   // GET /metrics
   private static class MetricsHandler implements HttpHandler {

      @Override
      public void handle(HttpExchange exchange) throws IOException {
         if (!"GET".equals(exchange.getRequestMethod())) {
            sendJson(exchange, 405, JsonResponses.error("Method not allowed"));
            return;
         }
         JsonObject obj = new JsonObject();
         obj.add("gpuTimings", Viewfinder.getMetricsCollector().toJson());
         sendJson(exchange, 200, JsonResponses.toJson(obj));
      }
   }

   // POST /ssbo
   private static class SsboHandler implements HttpHandler {

      @Override
      public void handle(HttpExchange exchange) throws IOException {
         if (!"POST".equals(exchange.getRequestMethod())) {
            sendJson(exchange, 405, JsonResponses.error("Method not allowed"));
            return;
         }
         try {
            Map<String, String> params = parseQueryParams(exchange.getRequestURI());
            int index = Integer.parseInt(params.getOrDefault("index", "0"));

            CompletableFuture<String> future = new CompletableFuture<>();
            Minecraft.getInstance().execute(() -> {
               try {
                  int bufferId = SSBOResolver.resolveBufferId(index);
                  if (bufferId == -1) {
                     future.complete(JsonResponses.error("No SSBO found at index " + index));
                     return;
                  }

                  String baseName = "ssbo_" + index;
                  String outputDir = Minecraft.getInstance().gameDirectory.toPath().resolve("ssbo_dumps").toString();
                  String outputPath = outputDir + "/" + baseName + ".bin";

                  JsonObject result = SSBODumper.dumpBuffer(bufferId, index, outputPath);
                  future.complete(JsonResponses.toJson(result));
               } catch (Exception e) {
                  future.complete(JsonResponses.error(e.getMessage()));
               }
            });
            sendRenderedJson(exchange, future.get(5, TimeUnit.SECONDS));
         } catch (NumberFormatException e) {
            sendJson(exchange, 400, JsonResponses.error("Invalid numeric parameter"));
         } catch (Exception e) {
            sendJson(exchange, 500, JsonResponses.error(e.getMessage()));
         }
      }
   }

   // GET /list-ssbo
   private static class SsboListHandler implements HttpHandler {

      @Override
      public void handle(HttpExchange exchange) throws IOException {
         if (!"GET".equals(exchange.getRequestMethod())) {
            sendJson(exchange, 405, JsonResponses.error("Method not allowed"));
            return;
         }
         try {
            CompletableFuture<String> future = new CompletableFuture<>();
            Minecraft.getInstance().execute(() -> {
               try {
                  JsonObject result = SSBOResolver.listBuffers();
                  future.complete(JsonResponses.toJson(result));
               } catch (Exception e) {
                  future.complete(JsonResponses.error(e.getMessage()));
               }
            });
            sendRenderedJson(exchange, future.get(5, TimeUnit.SECONDS));
         } catch (Exception e) {
            sendJson(exchange, 500, JsonResponses.error(e.getMessage()));
         }
      }
   }

   // GET /patched_shaders
   private static class PatchedShadersHandler implements HttpHandler {

      @Override
      public void handle(HttpExchange exchange) throws IOException {
         if (!"GET".equals(exchange.getRequestMethod())) {
            sendJson(exchange, 405, JsonResponses.error("Method not allowed"));
            return;
         }
         try {
            CompletableFuture<String> future = new CompletableFuture<>();
            Minecraft.getInstance().execute(() -> {
               try {
                  boolean debugEnabled = Iris.getIrisConfig().areDebugOptionsEnabled();
                  Path patchedDir = Minecraft.getInstance().gameDirectory.toPath().resolve("patched_shaders");

                  JsonArray files = new JsonArray();
                  if (Files.exists(patchedDir) && Files.isDirectory(patchedDir)) {
                     try (Stream<Path> stream = Files.list(patchedDir)) {
                        stream.forEach(p -> files.add(p.getFileName().toString()));
                     }
                  }

                  JsonObject obj = new JsonObject();
                  obj.addProperty("debugEnabled", debugEnabled);
                  obj.addProperty("path", patchedDir.toString());
                  obj.add("files", files);
                  future.complete(JsonResponses.toJson(obj));
               } catch (Exception e) {
                  future.complete(JsonResponses.error(e.getMessage()));
               }
            });
            sendRenderedJson(exchange, future.get(5, TimeUnit.SECONDS));
         } catch (Exception e) {
            sendJson(exchange, 500, JsonResponses.error(e.getMessage()));
         }
      }
   }

   // GET /textures
   private static class TexturesListHandler implements HttpHandler {

      @Override
      public void handle(HttpExchange exchange) throws IOException {
         if (!"GET".equals(exchange.getRequestMethod())) {
            sendJson(exchange, 405, JsonResponses.error("Method not allowed"));
            return;
         }
         try {
            CompletableFuture<String> future = new CompletableFuture<>();
            Minecraft.getInstance().execute(() -> {
               try {
                  JsonObject result = TextureResolver.listTextures();
                  future.complete(JsonResponses.toJson(result));
               } catch (Exception e) {
                  future.complete(JsonResponses.error(e.getMessage()));
               }
            });
            sendRenderedJson(exchange, future.get(5, TimeUnit.SECONDS));
         } catch (Exception e) {
            sendJson(exchange, 500, JsonResponses.error(e.getMessage()));
         }
      }
   }

   // POST /texture?name=colortex0  or  POST /texture?id=N
   private static class TextureHandler implements HttpHandler {

      @Override
      public void handle(HttpExchange exchange) throws IOException {
         if (!"POST".equals(exchange.getRequestMethod())) {
            sendJson(exchange, 405, JsonResponses.error("Method not allowed"));
            return;
         }
         try {
            Map<String, String> params = parseQueryParams(exchange.getRequestURI());
            String name = params.get("name");
            boolean raw = "true".equals(params.get("raw"));

            CompletableFuture<String> future = new CompletableFuture<>();
            Minecraft.getInstance().execute(() -> {
               try {
                  int textureId;
                  String baseName;

                  if (name != null) {
                     textureId = TextureResolver.resolveTexture(name);
                     if (textureId == -1) {
                        future.complete(JsonResponses.error("Unknown texture: " + name));
                        return;
                     }
                     baseName = name;
                  } else {
                     textureId = Integer.parseInt(params.getOrDefault("id", "0"));
                     baseName = "texture_" + textureId;
                  }

                  String ext = raw ? ".bin" : ".png";
                  String outputDir = Minecraft.getInstance().gameDirectory.toPath().resolve("texture_dumps").toString();
                  String outputPath = outputDir + "/" + baseName + ext;

                  JsonObject result = raw ? TextureDumper.dumpTextureRaw(textureId, outputPath) : TextureDumper.dumpTexture(textureId, outputPath);
                  if (name != null) {
                     result.addProperty("name", name);
                  }
                  future.complete(JsonResponses.toJson(result));
               } catch (Exception e) {
                  future.complete(JsonResponses.error(e.getMessage()));
               }
            });
            sendRenderedJson(exchange, future.get(5, TimeUnit.SECONDS));
         } catch (NumberFormatException e) {
            sendJson(exchange, 400, JsonResponses.error("Invalid texture id"));
         } catch (Exception e) {
            sendJson(exchange, 500, JsonResponses.error(e.getMessage()));
         }
      }
   }

   private static class RootHandler implements HttpHandler {

      @Override
      public void handle(HttpExchange exchange) throws IOException {
         if (!"GET".equals(exchange.getRequestMethod())) {
            sendJson(exchange, 405, JsonResponses.error("Method not allowed"));
            return;
         }

         JsonObject openapi = new JsonObject();
         openapi.addProperty("openapi", "3.0.0");

         // API Info
         JsonObject info = new JsonObject();
         info.addProperty("title", "Viewfinder HTTP API");
         info.addProperty("version", String.valueOf(Viewfinder.VERSION));
         info.addProperty("description", "Viewfinder API for external tools and scripts to test, automate, or integrate with shader development workflows.");
         openapi.add("info", info);

         // Components (Schemas)
         openapi.add("components", createComponents());

         // Paths
         JsonObject paths = new JsonObject();

         paths.add("/status", createPathItem("get", "Get shaderpack status", "StatusResponse"));
         paths.add("/reload", createPathItem("post", "Reload shaderpack", "ReloadResponse"));
         paths.add("/errors", createPathItem("get", "List captured shader errors", "ErrorsResponse"));

         // /screenshot (with query parameters)
         JsonObject screenshot = createPathItem("post", "Schedule screenshot", "ScreenshotResponse");
         screenshot
            .getAsJsonObject("post")
            .add("parameters", createQueryParameters(new String[] { "frames", "integer", "Number of frames to wait (default 1)" }));
         paths.add("/screenshot", screenshot);

         paths.add("/screenshot/result", createPathItem("get", "Get last screenshot path", "ScreenshotResultResponse"));
         paths.add("/metrics", createPathItem("get", "Get GPU timing metrics", "MetricsResponse"));

         // /ssbo (with query parameters)
         JsonObject ssbo = createPathItem("post", "Dump SSBO buffer", "GenericObjectResponse");
         ssbo
            .getAsJsonObject("post")
            .add("parameters", createQueryParameters(new String[] { "index", "integer", "Buffer index from shaders.properties (default 0)" }));
         paths.add("/ssbo", ssbo);
         paths.add("/list-ssbo", createPathItem("get", "List active SSBO buffers", "GenericObjectResponse"));

         paths.add("/patched_shaders", createPathItem("get", "List patched shaders", "PatchedShadersResponse"));
         paths.add("/list-textures", createPathItem("get", "List available textures", "GenericObjectResponse"));

         // /texture (with query parameters)
         JsonObject texture = createPathItem("post", "Dump texture to file", "GenericObjectResponse");
         texture
            .getAsJsonObject("post")
            .add(
               "parameters",
               createQueryParameters(
                  new String[] { "name", "string", "Dump by name (e.g., colortex0)" },
                  new String[] { "id", "integer", "Dump by GL id" },
                  new String[] { "raw", "boolean", "Dump raw data instead of PNG (default false)" }
               )
            );
         paths.add("/texture", texture);

         openapi.add("paths", paths);

         sendJson(exchange, 200, JsonResponses.toJson(openapi));
      }

      // --- OpenAPI Generation Helpers ---

      private JsonObject createComponents() {
         JsonObject components = new JsonObject();
         JsonObject schemas = new JsonObject();

         // Status Response
         schemas.add(
            "StatusResponse",
            createObjectSchema(createProperty("status", "string"), createProperty("pack_loaded", "boolean"), createProperty("shaderpack", "string"))
         );

         // Reload Response
         schemas.add(
            "ReloadResponse",
            createObjectSchema(
               createProperty("success", "boolean"),
               createProperty("errors", "object") // Can be expanded to actual error array schema
            )
         );

         // Errors Response
         schemas.add("ErrorsResponse", createObjectSchema(createProperty("errors", "object")));

         // Generic Success Response
         schemas.add("SuccessResponse", createObjectSchema(createProperty("success", "boolean")));

         // Screenshot Response
         schemas.add("ScreenshotResponse", createObjectSchema(createProperty("scheduled", "boolean"), createProperty("frames", "integer")));

         // Screenshot Result Response
         schemas.add(
            "ScreenshotResultResponse",
            createObjectSchema(
               createProperty("path", "string") // Nullable strings are tricky in strict OpenAPI 3.0, but 'string' works
            )
         );

         // Metrics Response
         schemas.add("MetricsResponse", createObjectSchema(createProperty("gpuTimings", "object")));

         // Patched Shaders Response
         schemas.add(
            "PatchedShadersResponse",
            createObjectSchema(createProperty("debugEnabled", "boolean"), createProperty("path", "string"), createArrayProperty("files", "string"))
         );

         // Generic Object Response (fallback for dynamic JSON outputs like SSBOs or Textures)
         JsonObject genericObject = new JsonObject();
         genericObject.addProperty("type", "object");
         schemas.add("GenericObjectResponse", genericObject);

         components.add("schemas", schemas);
         return components;
      }

      private JsonObject createPathItem(String method, String summary, String schemaRef) {
         JsonObject pathItem = new JsonObject();
         JsonObject operation = new JsonObject();
         operation.addProperty("summary", summary);

         JsonObject responses = new JsonObject();
         JsonObject okResponse = new JsonObject();
         okResponse.addProperty("description", "Successful operation");

         // Add the schema reference to the response body
         JsonObject content = new JsonObject();
         JsonObject applicationJson = new JsonObject();
         JsonObject schema = new JsonObject();
         schema.addProperty("$ref", "#/components/schemas/" + schemaRef);
         applicationJson.add("schema", schema);
         content.add("application/json", applicationJson);
         okResponse.add("content", content);

         responses.add("200", okResponse);
         operation.add("responses", responses);

         pathItem.add(method, operation);
         return pathItem;
      }

      private JsonArray createQueryParameters(String[]... params) {
         JsonArray jsonParams = new JsonArray();
         for (String[] param : params) {
            JsonObject p = new JsonObject();
            p.addProperty("name", param[0]);
            p.addProperty("in", "query");
            p.addProperty("description", param[2]);
            p.addProperty("required", false);

            JsonObject schema = new JsonObject();
            schema.addProperty("type", param[1]);
            p.add("schema", schema);

            jsonParams.add(p);
         }
         return jsonParams;
      }

      private JsonObject createObjectSchema(JsonObject... properties) {
         JsonObject schema = new JsonObject();
         schema.addProperty("type", "object");
         JsonObject props = new JsonObject();
         for (JsonObject p : properties) {
            String name = p.remove("___name___").getAsString(); // Extract temporary name tag
            props.add(name, p);
         }
         schema.add("properties", props);
         return schema;
      }

      private JsonObject createProperty(String name, String type) {
         JsonObject prop = new JsonObject();
         prop.addProperty("___name___", name); // Hidden tag for the assembler
         prop.addProperty("type", type);
         return prop;
      }

      private JsonObject createArrayProperty(String name, String itemsType) {
         JsonObject prop = new JsonObject();
         prop.addProperty("___name___", name); // Hidden tag for the assembler
         prop.addProperty("type", "array");
         JsonObject items = new JsonObject();
         items.addProperty("type", itemsType);
         prop.add("items", items);
         return prop;
      }
   }
}
