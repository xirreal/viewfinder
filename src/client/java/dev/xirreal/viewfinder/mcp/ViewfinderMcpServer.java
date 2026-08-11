package dev.xirreal.viewfinder.mcp;

import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpServer;
import dev.xirreal.viewfinder.Viewfinder;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpStatelessServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;

public final class ViewfinderMcpServer {
   public static final int PORT = 7150;
   public static final String ENDPOINT = "http://127.0.0.1:" + PORT + "/mcp";

   private final McpJsonMapper json = McpJsonDefaults.getMapper();
   private final JdkMcpHttpTransport transport = new JdkMcpHttpTransport(json);
   private HttpServer http;
   private ExecutorService executor;

   public void start() throws IOException {
      var server = McpServer.sync(transport)
         .serverInfo("viewfinder", Viewfinder.VERSION)
         .instructions("Inspect, capture, profile, edit, and reload the active Iris shaderpack. Mutation tools affect the running client; scene tools require singleplayer.")
         .requestTimeout(Duration.ofMinutes(2))
         .jsonMapper(json)
         .tools(tools())
         .resources(resources())
         .resourceTemplates(resourceTemplates())
         .build();

      http = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), PORT), 0);
      executor = Executors.newVirtualThreadPerTaskExecutor();
      http.setExecutor(executor);
      http.createContext("/mcp", exchange -> {
         if (!"/mcp".equals(exchange.getRequestURI().getPath())) {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
            return;
         }
         transport.handle(exchange);
      });
      http.start();
      Viewfinder.LOGGER.info("Viewfinder MCP server started at {}", ENDPOINT);
   }

   public void stop() {
      transport.closeGracefully().block();
      if (http != null) http.stop(0);
      if (executor != null) executor.close();
   }

   private List<McpStatelessServerFeatures.SyncToolSpecification> tools() {
      List<McpStatelessServerFeatures.SyncToolSpecification> tools = new ArrayList<>();
      tools.add(tool("get_diagnostics", "Get the current shaderpack, errors, render state, resources, metrics, and suggested actions.", object(), true,
         args -> ViewfinderOperations.diagnostics()));
      tools.add(tool("clear_diagnostics", "Clear captured shader diagnostics.", object(), false,
         args -> ViewfinderOperations.clearDiagnostics()));
      tools.add(tool("reload_shaders", "Reload the active Iris shaderpack and return fresh diagnostics.", object(), false,
         args -> ViewfinderOperations.reloadShaders()));
      tools.add(tool("set_shader_options", "Set active shaderpack option values and reload.", schema(
         property("options", "object", "Option names mapped to boolean or string values"), required("options")), false,
         ViewfinderOperations::setShaderOptions));
      tools.add(tool("write_shader_source", "Write a file inside a directory shaderpack's shaders folder, optionally reloading.", schema(
         property("path", "string", "Path relative to the shaderpack, beginning with shaders/"),
         property("source", "string", "Complete replacement source"), property("reload", "boolean", "Reload after writing"),
         required("path", "source")), false, ViewfinderOperations::writeShaderSource));
      tools.add(tool("inspect_program", "Reflect the current or specified OpenGL program, uniforms, UBOs, and SSBOs.", schema(
         property("id", "integer", "Optional OpenGL program id")), true, ViewfinderOperations::inspectProgram));
      tools.add(tool("dump_program_binary", "NVIDIA proprietary OpenGL driver only. Dump the current or specified program binary; NVIDIA blobs commonly contain readable pseudo-assembly useful for profiling and resource-access analysis, while AMD blobs are effectively meaningless for this workflow. Do not call this tool on non-NVIDIA drivers.", schema(
         property("id", "integer", "Optional OpenGL program id")), false, ViewfinderOperations::dumpProgramBinary));
      tools.add(tool("inspect_texture", "Inspect bounded texture metadata, pixel samples, and channel statistics by Iris name or GL id.", schema(
         property("name", "string", "Iris texture name such as colortex0"), property("id", "integer", "OpenGL texture id"),
         property("samples", "integer", "Maximum sampled pixels"), property("x", "integer", "Pixel x"),
         property("y", "integer", "Pixel y"), property("z", "integer", "Texture layer")), true, ViewfinderOperations::inspectTexture));
      tools.add(tool("dump_texture", "Dump an Iris texture or OpenGL texture id into the bounded capture store.", schema(
         property("name", "string", "Iris texture name"), property("id", "integer", "OpenGL texture id"),
         property("raw", "boolean", "Write raw bytes instead of PNG")), false, ViewfinderOperations::dumpTexture));
      tools.add(tool("inspect_ssbo", "Inspect bounded bytes and typed previews from an Iris shader storage buffer.", schema(
         property("index", "integer", "Iris SSBO binding index"), property("bytes", "integer", "Preview byte count")), true,
         ViewfinderOperations::inspectSsbo));
      tools.add(tool("dump_ssbo", "Dump an Iris shader storage buffer into the bounded capture store.", schema(
         property("index", "integer", "Iris SSBO binding index"), required("index")), false, ViewfinderOperations::dumpSsbo));
      tools.add(tool("capture_frame", "Capture a rendered frame after an optional delay.", schema(
         property("frames", "integer", "Frames to wait before capture")), false, ViewfinderOperations::captureFrame));
      tools.add(tool("profile_frames", "Collect hierarchical Iris GPU pass timings for the next fixed number of frames.", schema(
         property("frames", "integer", "Frames to profile, 1-600"),
         property("maxSeconds", "integer", "Wall-clock deadline, 5-110 seconds; returns a partial profile when reached")), false,
         ViewfinderOperations::profileFrames));
      tools.add(tool("capture_pass_outputs", "Capture bound framebuffer texture attachments when a named Iris pass next ends.", schema(
         property("pass", "string", "Observed leaf name or full pipeline path"),
         property("timeoutSeconds", "integer", "How long to wait for the pass"), required("pass")), false,
         ViewfinderOperations::capturePassOutputs));
      tools.add(tool("set_scene", "Set the authoritative singleplayer player pose, clock time, and weather.", schema(
         property("x", "number", "Player x"), property("y", "number", "Player y"), property("z", "number", "Player z"),
         property("yaw", "number", "Player yaw"), property("pitch", "number", "Player pitch"),
         property("time", "integer", "World clock ticks"), enumProperty("weather", "clear", "rain", "thunder")), false,
         ViewfinderOperations::setScene));
      tools.add(tool("control_ticks", "Freeze, resume, or step the integrated singleplayer server.", schema(
         enumProperty("action", "freeze", "resume", "step"), property("ticks", "integer", "Ticks to step"),
         property("tickRate", "number", "Optional server tick rate"), required("action")), false, ViewfinderOperations::controlTicks));
      return tools;
   }

   private List<McpStatelessServerFeatures.SyncResourceSpecification> resources() {
      return List.of(
         textResource("viewfinder://shaderpack/manifest", "shaderpack-manifest", "Active shaderpack sources and options",
            request -> ViewfinderOperations.shaderpackManifest().toString()),
         textResource("viewfinder://pipeline", "pipeline", "Observed hierarchical Iris render passes",
            request -> ViewfinderOperations.pipeline().toString())
      );
   }

   private List<McpStatelessServerFeatures.SyncResourceTemplateSpecification> resourceTemplates() {
      McpSchema.ResourceTemplate source = new McpSchema.ResourceTemplate("viewfinder://shaderpack/source/{path}",
         "shader-source", null, "Shaderpack source as loaded by Iris", "text/plain", null, null, null);
      McpSchema.ResourceTemplate patched = new McpSchema.ResourceTemplate("viewfinder://patched-shader/{name}",
         "patched-shader", null, "Iris patched shader source", "text/plain", null, null, null);
      McpSchema.ResourceTemplate capture = new McpSchema.ResourceTemplate("viewfinder://capture/{captureId}/{file}",
         "capture", null, "File produced by a Viewfinder capture tool", "application/octet-stream", null, null, null);
      return List.of(
         new McpStatelessServerFeatures.SyncResourceTemplateSpecification(source, (ctx, request) ->
            text(request.uri(), ViewfinderOperations.shaderSource(after(request.uri(), "viewfinder://shaderpack/source/")))),
         new McpStatelessServerFeatures.SyncResourceTemplateSpecification(patched, (ctx, request) ->
            text(request.uri(), ViewfinderOperations.patchedShader(after(request.uri(), "viewfinder://patched-shader/")))),
         new McpStatelessServerFeatures.SyncResourceTemplateSpecification(capture, (ctx, request) -> binaryCapture(request.uri()))
      );
   }

   private McpStatelessServerFeatures.SyncToolSpecification tool(String name, String description,
      Map<String, Object> inputSchema, boolean readOnly, Function<Map<String, Object>, JsonObject> operation) {
      McpSchema.ToolAnnotations annotations = new McpSchema.ToolAnnotations(null, readOnly, !readOnly, readOnly, false, false);
      McpSchema.Tool definition = new McpSchema.Tool(name, null, description, inputSchema, null, annotations, null, null);
      return new McpStatelessServerFeatures.SyncToolSpecification(definition, (ctx, request) -> {
         try {
            JsonObject result = operation.apply(request.arguments() == null ? Map.of() : request.arguments());
            if (!result.has("message")) result.addProperty("message", userMessage(name, result));
            return new McpSchema.CallToolResult(contents(result),
               false, ViewfinderOperations.structured(result), null);
         } catch (Exception e) {
            String message = rootMessage(e);
            return new McpSchema.CallToolResult(List.of(new McpSchema.TextContent(null, message, null)), true,
               Map.of("error", message), null);
         }
      });
   }

   private static List<McpSchema.Content> contents(JsonObject result) {
      List<McpSchema.Content> contents = new ArrayList<>();
      contents.add(new McpSchema.TextContent(null, summary(result), null));
      if (result.has("resourceUri")) appendImage(contents, result.get("resourceUri").getAsString());
      if (result.has("outputs") && result.get("outputs").isJsonArray()) {
         result.getAsJsonArray("outputs").forEach(output -> {
            JsonObject object = output.getAsJsonObject();
            if (object.has("resourceUri")) appendImage(contents, object.get("resourceUri").getAsString());
         });
      }
      return contents;
   }

   private static void appendImage(List<McpSchema.Content> contents, String uri) {
      if (!uri.endsWith(".png")) return;
      try {
         String rest = after(uri, "viewfinder://capture/");
         int slash = rest.indexOf('/');
         if (slash < 1) return;
         Path file = ViewfinderOperations.captureFile(rest.substring(0, slash), rest.substring(slash + 1));
         if (Files.size(file) > 8 * 1024 * 1024) return;
         String data = Base64.getEncoder().encodeToString(Files.readAllBytes(file));
         contents.add(new McpSchema.ImageContent(null, data, "image/png", null));
      } catch (Exception ignored) {
         // The resource URI remains available when an image is too large or disappears.
      }
   }

   private McpStatelessServerFeatures.SyncResourceSpecification textResource(String uri, String name,
      String description, Function<McpSchema.ReadResourceRequest, String> reader) {
      McpSchema.Resource resource = new McpSchema.Resource(uri, name, null, description, "application/json", null, null, null, null);
      return new McpStatelessServerFeatures.SyncResourceSpecification(resource, (ctx, request) -> text(request.uri(), reader.apply(request)));
   }

   private static McpSchema.ReadResourceResult text(String uri, String value) {
      return new McpSchema.ReadResourceResult(List.of(new McpSchema.TextResourceContents(uri, "text/plain", value, null)), null);
   }

   private static McpSchema.ReadResourceResult binaryCapture(String uri) {
      String rest = after(uri, "viewfinder://capture/");
      int slash = rest.indexOf('/');
      if (slash < 1) throw new IllegalArgumentException("Capture URI must include capture id and file");
      Path file = ViewfinderOperations.captureFile(rest.substring(0, slash), rest.substring(slash + 1));
      try {
         String mime = Files.probeContentType(file);
         if (mime == null) mime = "application/octet-stream";
         String data = Base64.getEncoder().encodeToString(Files.readAllBytes(file));
         return new McpSchema.ReadResourceResult(List.of(new McpSchema.BlobResourceContents(uri, mime, data, null)), null);
      } catch (IOException e) {
         throw new IllegalStateException(e);
      }
   }

   private static Map<String, Object> object() {
      return Map.of("type", "object", "additionalProperties", false);
   }

   @SafeVarargs
   private static Map<String, Object> schema(Map.Entry<String, Object>... entries) {
      Map<String, Object> schema = new LinkedHashMap<>();
      schema.put("type", "object");
      schema.put("additionalProperties", false);
      Map<String, Object> properties = new LinkedHashMap<>();
      for (Map.Entry<String, Object> entry : entries) {
         if (entry.getKey().equals("required")) schema.put("required", entry.getValue());
         else properties.put(entry.getKey(), entry.getValue());
      }
      schema.put("properties", properties);
      return schema;
   }

   private static Map.Entry<String, Object> property(String name, String type, String description) {
      return Map.entry(name, Map.of("type", type, "description", description));
   }

   private static Map.Entry<String, Object> enumProperty(String name, String... values) {
      return Map.entry(name, Map.of("type", "string", "enum", List.of(values)));
   }

   private static Map.Entry<String, Object> required(String... names) {
      return Map.entry("required", List.of(names));
   }

   private static String after(String uri, String prefix) {
      if (!uri.startsWith(prefix)) throw new IllegalArgumentException("Unexpected resource URI");
      return URLDecoder.decode(uri.substring(prefix.length()), StandardCharsets.UTF_8);
   }

   private static String summary(JsonObject result) {
      if (result.has("message")) return result.get("message").getAsString();
      if (result.has("error")) return result.get("error").getAsString();
      return "Viewfinder operation completed";
   }

   private static String userMessage(String tool, JsonObject result) {
      return switch (tool) {
         case "get_diagnostics" -> "Diagnostics collected";
         case "inspect_program" -> result.has("programId") ? "Reflected OpenGL program " + result.get("programId").getAsInt()
            : "Program inspection completed";
         case "dump_program_binary" -> result.has("captureId") ? "Program binary written to capture " + result.get("captureId").getAsString()
            : "Program binary dump completed";
         case "inspect_texture" -> result.has("name") ? "Inspected texture " + result.get("name").getAsString()
            : result.has("textureId") ? "Inspected texture GL " + result.get("textureId").getAsInt()
            : "Texture inspection completed";
         case "dump_texture" -> result.has("captureId") ? "Texture dump written to capture " + result.get("captureId").getAsString()
            : "Texture dump completed";
         case "inspect_ssbo" -> result.has("index") ? "Inspected SSBO " + result.get("index").getAsInt()
            : "SSBO inspection completed";
         case "dump_ssbo" -> result.has("captureId") ? "SSBO dump written to capture " + result.get("captureId").getAsString()
            : "SSBO dump completed";
         case "capture_pass_outputs" -> result.has("pass") ? "Pass outputs captured for " + result.get("pass").getAsString()
            : "Pass output capture completed";
         default -> "Viewfinder " + tool + " completed";
      };
   }

   private static String rootMessage(Throwable error) {
      Throwable root = error;
      while (root.getCause() != null) root = root.getCause();
      return root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();
   }
}
