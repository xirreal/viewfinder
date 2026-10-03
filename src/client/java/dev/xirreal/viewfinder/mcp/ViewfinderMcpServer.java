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
import java.util.function.Supplier;

public final class ViewfinderMcpServer {
   public static final int PORT = 7150;
   public static final String ENDPOINT = "http://127.0.0.1:" + PORT + "/mcp";
   private static final long MAX_RESOURCE_BYTES = 16L * 1024 * 1024;

   private final McpJsonMapper json = McpJsonDefaults.getMapper();
   private final JdkMcpHttpTransport transport = new JdkMcpHttpTransport(json);
   private final McpRequestQueue requestQueue = new McpRequestQueue();
   private HttpServer http;
   private ExecutorService executor;

   public void start() throws IOException {
      var server = McpServer.sync(transport)
         .serverInfo("viewfinder", Viewfinder.VERSION)
         .instructions("Inspect, capture, profile, edit, switch, and reload Iris shaderpacks. Prefer run_actions for dependent steps: all actions are validated before action zero, then execute in order without interleaving. Reload-capable tools return complete fresh errors and parsed compilerMessages inline; inspect those directly instead of calling reload_shaders or get_diagnostics again. Use write_shader_sources for multiple files so they reload once. Prefer stable Iris names over GL ids; identifiers are never inferred. GPU profiles return summaries unless includeSamples is true. For deterministic scenes, freeze ticks before set_scene in the same run_actions job and resume when finished. All standalone tools share the same bounded queue. Mutation tools affect the running client; scene tools require singleplayer.")
         .requestTimeout(Duration.ofMinutes(30))
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
      requestQueue.close();
      if (http != null) http.stop(0);
      if (executor != null) executor.close();
   }

   private List<McpStatelessServerFeatures.SyncToolSpecification> tools() {
      List<McpStatelessServerFeatures.SyncToolSpecification> tools = new ArrayList<>();
      tools.add(tool("get_mcp_status", "Inspect the shared MCP execution queue while another request is running.", ViewfinderToolSchemas.input("get_mcp_status"), true,
         args -> queueStatus(), false));
      tools.add(tool("run_actions", "Run 1-64 dependent actions as one queued, non-interleavable MCP job. Every action is validated before action zero executes. Reload-capable actions already return fresh errors; do not add get_diagnostics solely to check them. Most action types match existing tools; wait_frames, list_programs, list_textures, and list_ssbos are action-only. Supported types: " + ViewfinderToolSchemas.actionTypeDescription(), ViewfinderToolSchemas.input("run_actions"), false,
         ViewfinderOperations::runActions));
      tools.add(tool("get_diagnostics", "Get the current shaderpack, complete captured errors with parsed compiler messages, render state, resources, summarized metrics, and suggested actions. Raw timing samples are opt-in.", ViewfinderToolSchemas.input("get_diagnostics"), true,
         ViewfinderOperations::diagnostics));
      tools.add(tool("clear_diagnostics", "Clear captured shader diagnostics.", ViewfinderToolSchemas.input("clear_diagnostics"), false,
         args -> ViewfinderOperations.clearDiagnostics()));
      tools.add(tool("reload_shaders", "Reload the active Iris shaderpack. Returns complete fresh errors with parsed compiler messages when present, so inspect those directly instead of following with get_diagnostics.", ViewfinderToolSchemas.input("reload_shaders"), false,
         args -> ViewfinderOperations.reloadShaders()));
      tools.add(tool("list_shaderpacks", "List installed Iris shaderpacks, the selected pack, and its effective option config.", ViewfinderToolSchemas.input("list_shaderpacks"), true,
         args -> ViewfinderOperations.listShaderpacks()));
      tools.add(tool("switch_shaderpack", "Switch to an installed Iris shaderpack, optionally apply an option config, reload once, and return complete fresh errors; no follow-up reload or diagnostics call is needed.", ViewfinderToolSchemas.input("switch_shaderpack"), false,
         ViewfinderOperations::switchShaderpack));
      tools.add(tool("set_shader_options", "Set active shaderpack option values, reload once, and return complete fresh errors; use reset with an empty object to restore defaults.", ViewfinderToolSchemas.input("set_shader_options"), false,
         ViewfinderOperations::setShaderOptions));
      tools.add(tool("write_shader_source", "Write one file inside a directory shaderpack's shaders folder and optionally reload. Prefer write_shader_sources when changing multiple files.", ViewfinderToolSchemas.input("write_shader_source"), false,
         ViewfinderOperations::writeShaderSource));
      tools.add(tool("write_shader_sources", "Write 1-64 files inside a directory shaderpack's shaders folder, validating every path first and reloading at most once.", ViewfinderToolSchemas.input("write_shader_sources"), false,
         ViewfinderOperations::writeShaderSources));
      tools.add(tool("inspect_program", "Reflect an Iris program. Prefer its exact Iris name because names survive reloads; use a GL id only when no name exists. Exactly one selector is required.", ViewfinderToolSchemas.input("inspect_program"), true,
         ViewfinderOperations::inspectProgram));
      tools.add(tool("dump_program_binary", "NVIDIA proprietary OpenGL driver only. Dump and inline up to 16 MiB of a program's complete pseudo-assembly. Prefer its exact Iris name because names survive reloads; use a GL id only when no name exists. Exactly one selector is required; unsupported drivers are rejected.", ViewfinderToolSchemas.input("dump_program_binary"), false,
         ViewfinderOperations::dumpProgramBinary));
      tools.add(tool("inspect_texture", "Inspect bounded texture metadata, pixel samples, and channel statistics. Prefer an Iris texture name; use a GL id only when no name exists. Exactly one selector is required.", ViewfinderToolSchemas.input("inspect_texture"), true,
         ViewfinderOperations::inspectTexture));
      tools.add(tool("dump_texture", "Dump a texture into the bounded capture store. Prefer an Iris texture name; use a GL id only when no name exists. Exactly one selector is required.", ViewfinderToolSchemas.input("dump_texture"), false,
         ViewfinderOperations::dumpTexture));
      tools.add(tool("inspect_ssbo", "Inspect bounded bytes and typed previews from an explicit Iris shader storage buffer index.", ViewfinderToolSchemas.input("inspect_ssbo"), true,
         ViewfinderOperations::inspectSsbo));
      tools.add(tool("dump_ssbo", "Dump an explicit Iris shader storage buffer index into the bounded capture store.", ViewfinderToolSchemas.input("dump_ssbo"), false,
         ViewfinderOperations::dumpSsbo));
      tools.add(tool("capture_frame", "Capture a rendered frame after an optional delay.", ViewfinderToolSchemas.input("capture_frame"), false,
         ViewfinderOperations::captureFrame));
      tools.add(tool("profile_frames", "Collect summarized hierarchical Iris GPU pass timings for the next fixed number of frames; raw samples are opt-in.", ViewfinderToolSchemas.input("profile_frames"), false,
         ViewfinderOperations::profileFrames));
      tools.add(tool("capture_pass_outputs", "Capture bound framebuffer texture attachments when an exact named Iris pass next ends; timeout failures return observed pass choices.", ViewfinderToolSchemas.input("capture_pass_outputs"), false,
         ViewfinderOperations::capturePassOutputs));
      tools.add(tool("get_render_settings", "Query configured and effective client render distance in chunks and base FOV in degrees. Effective distance may be limited by the server; gameplay FOV effects are excluded.", ViewfinderToolSchemas.input("get_render_settings"), true,
         args -> ViewfinderOperations.getRenderSettings()));
      tools.add(tool("set_render_settings", "Set client render distance (2-32 chunks), base FOV (30-110 degrees), or both, and save Minecraft options. Returns the resulting settings. Works in singleplayer and multiplayer; wait_frames before dependent captures or profiles.", ViewfinderToolSchemas.input("set_render_settings"), false,
         ViewfinderOperations::setRenderSettings));
      tools.add(tool("set_scene", "Set at least one authoritative singleplayer player pose, clock, or weather field. For deterministic setup, freeze ticks first in the same run_actions job.", ViewfinderToolSchemas.input("set_scene"), false,
         ViewfinderOperations::setScene));
      tools.add(tool("control_ticks", "Freeze, resume, or step the integrated singleplayer server.", ViewfinderToolSchemas.input("control_ticks"), false,
         ViewfinderOperations::controlTicks));
      return tools;
   }

   private List<McpStatelessServerFeatures.SyncResourceSpecification> resources() {
      return List.of(
         textResource("viewfinder://shaderpack/manifest", "shaderpack-manifest", "Active shaderpack sources and options",
            request -> queued("read_shaderpack_manifest", () -> ViewfinderOperations.shaderpackManifest().toString())),
         textResource("viewfinder://pipeline", "pipeline", "Observed hierarchical Iris render passes",
            request -> queued("read_pipeline", () -> ViewfinderOperations.pipeline().toString()))
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
            text(request.uri(), queued("read_shader_source", () ->
               ViewfinderOperations.shaderSource(after(request.uri(), "viewfinder://shaderpack/source/"))))),
         new McpStatelessServerFeatures.SyncResourceTemplateSpecification(patched, (ctx, request) ->
            text(request.uri(), queued("read_patched_shader", () ->
               ViewfinderOperations.patchedShader(after(request.uri(), "viewfinder://patched-shader/"))))),
         new McpStatelessServerFeatures.SyncResourceTemplateSpecification(capture, (ctx, request) -> binaryCapture(request.uri()))
      );
   }

   private McpStatelessServerFeatures.SyncToolSpecification tool(String name, String description,
      Map<String, Object> inputSchema, boolean readOnly, Function<Map<String, Object>, JsonObject> operation) {
      return tool(name, description, inputSchema, readOnly, operation, true);
   }

   private McpStatelessServerFeatures.SyncToolSpecification tool(String name, String description,
      Map<String, Object> inputSchema, boolean readOnly, Function<Map<String, Object>, JsonObject> operation, boolean queued) {
      McpSchema.ToolAnnotations annotations = new McpSchema.ToolAnnotations(null, readOnly, !readOnly, readOnly, false, false);
      McpSchema.Tool definition = new McpSchema.Tool(name, null, description, inputSchema, null, annotations, null, null);
      return new McpStatelessServerFeatures.SyncToolSpecification(definition, (ctx, request) -> {
         try {
            Map<String, Object> arguments = request.arguments() == null ? Map.of() : request.arguments();
            ViewfinderToolSchemas.validate(inputSchema, arguments, "Tool " + name);
            McpRequestQueue.Result<JsonObject> execution = queued ? requestQueue.execute(name, () -> operation.apply(arguments)) : null;
            JsonObject result = execution == null ? operation.apply(arguments) : execution.value();
            if (execution != null) result.add("request", requestMetadata(execution));
            if (!result.has("message")) result.addProperty("message", summary(result));
            return new McpSchema.CallToolResult(contents(result),
               !ViewfinderOperations.succeeded(result), ViewfinderOperations.structured(result), null);
         } catch (Exception e) {
            String message = rootMessage(e);
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("error", message);
            if (e instanceof McpRequestQueue.QueueFullException) {
               error.put("code", "QUEUE_FULL");
               error.put("retryable", true);
            }
            return new McpSchema.CallToolResult(List.of(new McpSchema.TextContent(null, message, null)), true,
               error, null);
         }
      });
   }

   private JsonObject queueStatus() {
      McpRequestQueue.Snapshot snapshot = requestQueue.snapshot();
      JsonObject result = new JsonObject();
      result.addProperty("success", true);
      result.addProperty("state", snapshot.state());
      result.addProperty("queuedRequests", snapshot.queuedRequests());
      result.addProperty("queueCapacity", snapshot.capacity());
      result.addProperty("submittedRequests", snapshot.submittedRequests());
      result.addProperty("completedRequests", snapshot.completedRequests());
      result.addProperty("rejectedRequests", snapshot.rejectedRequests());
      if (snapshot.active() != null) {
         JsonObject active = new JsonObject();
         active.addProperty("requestId", snapshot.active().requestId());
         active.addProperty("operation", snapshot.active().operation());
         active.addProperty("startedAt", snapshot.active().startedAtMillis());
         result.add("activeRequest", active);
      }
      result.addProperty("message", snapshot.active() != null
         ? "MCP request " + snapshot.active().requestId() + " is running; " + snapshot.queuedRequests() + " queued"
         : snapshot.queuedRequests() > 0 ? snapshot.queuedRequests() + " MCP request(s) queued" : "MCP execution queue is idle");
      return result;
   }

   private static JsonObject requestMetadata(McpRequestQueue.Result<?> execution) {
      JsonObject request = new JsonObject();
      request.addProperty("requestId", execution.requestId());
      request.addProperty("initialQueuePosition", execution.queuePosition());
      request.addProperty("queuedMillis", execution.queuedMillis());
      request.addProperty("executionMillis", execution.executionMillis());
      return request;
   }

   private <T> T queued(String operation, Supplier<T> task) {
      return requestQueue.execute(operation, task).value();
   }

   private static List<McpSchema.Content> contents(JsonObject result) {
      List<McpSchema.Content> contents = new ArrayList<>();
      contents.add(new McpSchema.TextContent(null, summary(result), null));
      appendResultContent(contents, result);
      if (result.has("actionResults") && result.get("actionResults").isJsonArray()) {
         result.getAsJsonArray("actionResults").forEach(action -> {
            JsonObject object = action.getAsJsonObject();
            if (object.has("result") && object.get("result").isJsonObject()) {
               appendResultContent(contents, object.getAsJsonObject("result"));
            }
         });
      }
      return contents;
   }

   private static void appendResultContent(List<McpSchema.Content> contents, JsonObject result) {
      if (result.has("resourceUri")) appendImage(contents, result.get("resourceUri").getAsString());
      appendProgramBinary(contents, result);
      if (!result.has("outputs") || !result.get("outputs").isJsonArray()) return;
      result.getAsJsonArray("outputs").forEach(output -> {
         JsonObject object = output.getAsJsonObject();
         if (object.has("resourceUri")) appendImage(contents, object.get("resourceUri").getAsString());
      });
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

   private static void appendProgramBinary(List<McpSchema.Content> contents, JsonObject result) {
      if (!result.has("binaryFormat") || !result.has("resourceUri")) return;
      String uri = result.get("resourceUri").getAsString();
      try {
         String rest = after(uri, "viewfinder://capture/");
         int slash = rest.indexOf('/');
         if (slash < 1) return;
         Path file = ViewfinderOperations.captureFile(rest.substring(0, slash), rest.substring(slash + 1));
         long bytes = Files.size(file);
         result.addProperty("assemblyBytes", bytes);
         if (bytes > MAX_RESOURCE_BYTES) {
            result.addProperty("assemblyInlined", false);
            result.addProperty("assemblyInlineLimitBytes", MAX_RESOURCE_BYTES);
            return;
         }
         contents.add(programAssemblyContent(uri, Files.readAllBytes(file)));
         result.addProperty("assemblyInlined", true);
      } catch (Exception ignored) {
         result.addProperty("assemblyInlined", false);
      }
   }

   static McpSchema.EmbeddedResource programAssemblyContent(String uri, byte[] binary) {
      return new McpSchema.EmbeddedResource(null,
         new McpSchema.TextResourceContents(uri, "text/x-nvidia-assembly",
            new String(binary, StandardCharsets.UTF_8), null));
   }

   private McpStatelessServerFeatures.SyncResourceSpecification textResource(String uri, String name,
      String description, Function<McpSchema.ReadResourceRequest, String> reader) {
      McpSchema.Resource resource = new McpSchema.Resource(uri, name, null, description, "application/json", null, null, null, null);
      return new McpStatelessServerFeatures.SyncResourceSpecification(resource,
         (ctx, request) -> text(request.uri(), "application/json", reader.apply(request)));
   }

   private static McpSchema.ReadResourceResult text(String uri, String value) {
      return text(uri, "text/plain", value);
   }

   private static McpSchema.ReadResourceResult text(String uri, String mimeType, String value) {
      return new McpSchema.ReadResourceResult(List.of(new McpSchema.TextResourceContents(uri, mimeType, value, null)), null);
   }

   private static McpSchema.ReadResourceResult binaryCapture(String uri) {
      String rest = after(uri, "viewfinder://capture/");
      int slash = rest.indexOf('/');
      if (slash < 1) throw new IllegalArgumentException("Capture URI must include capture id and file");
      Path file = ViewfinderOperations.captureFile(rest.substring(0, slash), rest.substring(slash + 1));
      try {
         long bytes = Files.size(file);
         if (bytes > MAX_RESOURCE_BYTES) {
            throw new IllegalArgumentException("Capture exceeds the 16 MiB MCP resource limit: " + bytes + " bytes");
         }
         String mime = Files.probeContentType(file);
         if (mime == null) mime = "application/octet-stream";
         String data = Base64.getEncoder().encodeToString(Files.readAllBytes(file));
         return new McpSchema.ReadResourceResult(List.of(new McpSchema.BlobResourceContents(uri, mime, data, null)), null);
      } catch (IOException e) {
         throw new IllegalStateException(e);
      }
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

   private static String rootMessage(Throwable error) {
      Throwable root = error;
      while (root.getCause() != null) root = root.getCause();
      return root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();
   }
}
