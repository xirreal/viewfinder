package dev.xirreal.viewfinder.mcp;

import com.sun.net.httpserver.HttpExchange;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpStatelessServerHandler;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpStatelessServerTransport;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import reactor.core.publisher.Mono;

/** Stateless MCP transport backed by Minecraft's existing JDK runtime only. */
public final class JdkMcpHttpTransport implements McpStatelessServerTransport {
   private static final int MAX_REQUEST_BYTES = 4 * 1024 * 1024;
   private final McpJsonMapper json;
   private volatile McpStatelessServerHandler handler;
   private volatile boolean closing;

   public JdkMcpHttpTransport(McpJsonMapper json) {
      this.json = json;
   }

   @Override
   public void setMcpHandler(McpStatelessServerHandler handler) {
      this.handler = handler;
   }

   @Override
   public Mono<Void> closeGracefully() {
      return Mono.fromRunnable(() -> closing = true);
   }

   public void handle(HttpExchange exchange) throws IOException {
      try {
         if (!isSafeLoopbackRequest(exchange)) {
            send(exchange, 403, "text/plain", "Forbidden");
            return;
         }
         if (closing) {
            send(exchange, 503, "text/plain", "Server is shutting down");
            return;
         }
         if (!"POST".equals(exchange.getRequestMethod())) {
            exchange.getResponseHeaders().set("Allow", "POST");
            send(exchange, 405, "text/plain", "MCP uses POST /mcp");
            return;
         }
         String accept = exchange.getRequestHeaders().getFirst("Accept");
         if (!listsMediaType(accept, "application/json") || !listsMediaType(accept, "text/event-stream")) {
            sendError(exchange, 400, McpSchema.ErrorCodes.INVALID_REQUEST,
               "Accept must include application/json and text/event-stream");
            return;
         }
         String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
         if (!isMediaType(contentType, "application/json")) {
            sendError(exchange, 415, McpSchema.ErrorCodes.INVALID_REQUEST,
               "Content-Type must be application/json");
            return;
         }

         byte[] body = exchange.getRequestBody().readNBytes(MAX_REQUEST_BYTES + 1);
         if (body.length > MAX_REQUEST_BYTES) {
            sendError(exchange, 413, McpSchema.ErrorCodes.INVALID_REQUEST, "Request body is too large");
            return;
         }
         McpSchema.JSONRPCMessage message;
         try {
            message = McpSchema.deserializeJsonRpcMessage(json, new String(body, StandardCharsets.UTF_8));
         } catch (IOException e) {
            sendError(exchange, 400, McpSchema.ErrorCodes.PARSE_ERROR, "Invalid JSON");
            return;
         } catch (IllegalArgumentException e) {
            sendError(exchange, 400, McpSchema.ErrorCodes.INVALID_REQUEST, "Invalid JSON-RPC message");
            return;
         }
         McpStatelessServerHandler current = handler;
         if (current == null) throw new IllegalStateException("MCP handler is not initialized");

         if (message instanceof McpSchema.JSONRPCRequest request) {
            McpSchema.JSONRPCResponse response = current.handleRequest(McpTransportContext.EMPTY, request).block();
            send(exchange, 200, "application/json", json.writeValueAsString(response));
         } else if (message instanceof McpSchema.JSONRPCNotification notification) {
            current.handleNotification(McpTransportContext.EMPTY, notification).block();
            exchange.sendResponseHeaders(202, -1);
         } else {
            sendError(exchange, 400, McpSchema.ErrorCodes.INVALID_REQUEST,
               "Expected a JSON-RPC request or notification");
         }
      } catch (IllegalArgumentException e) {
         sendError(exchange, 400, McpSchema.ErrorCodes.INVALID_REQUEST, "Invalid JSON-RPC message");
      } catch (Exception e) {
         sendError(exchange, 500, McpSchema.ErrorCodes.INTERNAL_ERROR,
            e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
      } finally {
         exchange.close();
      }
   }

   static boolean isSafeLoopbackRequest(HttpExchange exchange) {
      InetAddress remote = exchange.getRemoteAddress().getAddress();
      if (remote == null || !remote.isLoopbackAddress()) return false;

      String host = exchange.getRequestHeaders().getFirst("Host");
      if (host == null) return false;
      String normalizedHost = host.toLowerCase(Locale.ROOT);
      if (!(normalizedHost.equals("localhost") || normalizedHost.startsWith("localhost:")
         || normalizedHost.equals("127.0.0.1") || normalizedHost.startsWith("127.0.0.1:")
         || normalizedHost.equals("[::1]") || normalizedHost.startsWith("[::1]:"))) return false;

      return isSafeOrigin(exchange.getRequestHeaders().getFirst("Origin"));
   }

   static boolean isSafeOrigin(String origin) {
      if (origin == null) return true;
      try {
         URI uri = URI.create(origin);
         String scheme = uri.getScheme();
         String originHost = uri.getHost();
         return ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
            && originHost != null && (originHost.equalsIgnoreCase("localhost")
               || originHost.equals("127.0.0.1") || originHost.equals("::1") || originHost.equals("[::1]"));
      } catch (Exception ignored) {
         return false;
      }
   }

   private void sendError(HttpExchange exchange, int status, int code, String message) throws IOException {
      Map<String, Object> response = new LinkedHashMap<>();
      response.put("jsonrpc", "2.0");
      response.put("id", null);
      response.put("error", Map.of("code", code, "message", message));
      send(exchange, status, "application/json", json.writeValueAsString(response));
   }

   private static boolean listsMediaType(String value, String expected) {
      if (value == null) return false;
      for (String item : value.split(",")) {
         if (isMediaType(item, expected)) return true;
      }
      return false;
   }

   private static boolean isMediaType(String value, String expected) {
      if (value == null) return false;
      int parameters = value.indexOf(';');
      String mediaType = parameters < 0 ? value : value.substring(0, parameters);
      return mediaType.trim().equalsIgnoreCase(expected);
   }

   private static void send(HttpExchange exchange, int status, String contentType, String body) throws IOException {
      byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", contentType + "; charset=utf-8");
      exchange.getResponseHeaders().set("Cache-Control", "no-store");
      exchange.sendResponseHeaders(status, bytes.length);
      exchange.getResponseBody().write(bytes);
   }
}
