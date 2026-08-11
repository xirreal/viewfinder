package dev.xirreal.viewfinder.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.spec.McpSchema;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class JdkMcpHttpTransportTest {
   private HttpServer http;
   private URI endpoint;

   @BeforeEach
   void start() throws Exception {
      JdkMcpHttpTransport transport = new JdkMcpHttpTransport(McpJsonDefaults.getMapper());
      McpSchema.Tool ping = new McpSchema.Tool("ping", null, "test tool",
         Map.of("type", "object", "additionalProperties", false), null, null, null, null);
      McpServer.sync(transport).serverInfo("test-server", "1")
         .toolCall(ping, (context, request) -> new McpSchema.CallToolResult(
            List.of(new McpSchema.TextContent(null, "pong", null)), false, Map.of("value", "pong"), null))
         .build();
      http = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
      http.createContext("/mcp", transport::handle);
      http.start();
      endpoint = URI.create("http://127.0.0.1:" + http.getAddress().getPort() + "/mcp");
   }

   @AfterEach
   void stop() {
      http.stop(0);
   }

   @Test
   void initializesAndListsToolsOverStreamableHttp() throws Exception {
      HttpResponse<String> initialized = post("""
         {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"test","version":"1"}}}
         """, null);
      assertEquals(200, initialized.statusCode());
      assertTrue(initialized.body().contains("test-server"));

      HttpResponse<String> tools = post("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\",\"params\":{}}", null);
      assertEquals(200, tools.statusCode());
      assertTrue(tools.body().contains("ping"));
   }

   @Test
   void rejectsBrowserOriginsOutsideLoopback() throws Exception {
      HttpResponse<String> response = post("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\",\"params\":{}}", "https://example.com");
      assertEquals(403, response.statusCode());
   }

   private HttpResponse<String> post(String body, String origin) throws Exception {
      HttpRequest.Builder request = HttpRequest.newBuilder(endpoint)
         .header("Accept", "application/json, text/event-stream")
         .header("Content-Type", "application/json")
         .POST(HttpRequest.BodyPublishers.ofString(body));
      if (origin != null) request.header("Origin", origin);
      return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
   }
}
