package dev.xirreal.viewfinder.mcp;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ViewfinderToolSchemasTest {
   @Test
   void everyPublishedContractIsValidJsonSchema() {
      List<String> tools = new java.util.ArrayList<>(ViewfinderToolSchemas.actionTypes());
      tools.add("get_mcp_status");
      tools.add("run_actions");
      tools.forEach(tool -> assertTrue(
         McpJsonDefaults.getSchemaValidator().validateSchema(ViewfinderToolSchemas.input(tool)).valid(), tool));
   }

   @Test
   void selectorsRequireOneNonDefaultedNameOrId() {
      assertDoesNotThrow(() -> ViewfinderToolSchemas.validate(
         ViewfinderToolSchemas.input("inspect_program"), Map.of("name", "composite"), "program"));
      assertDoesNotThrow(() -> ViewfinderToolSchemas.validate(
         ViewfinderToolSchemas.input("inspect_texture"), Map.of("id", 23), "texture"));

      assertThrows(IllegalArgumentException.class, () -> ViewfinderToolSchemas.validate(
         ViewfinderToolSchemas.input("inspect_program"), Map.of(), "program"));
      assertThrows(IllegalArgumentException.class, () -> ViewfinderToolSchemas.validate(
         ViewfinderToolSchemas.input("inspect_program"), Map.of("name", "composite", "id", 17), "program"));
      assertThrows(IllegalArgumentException.class, () -> ViewfinderToolSchemas.validate(
         ViewfinderToolSchemas.input("inspect_texture"), Map.of("id", 0), "texture"));
   }

   @Test
   void runActionsPublishesStrictPerActionContracts() {
      Map<String, Object> valid = Map.of("actions", List.of(
         Map.of("type", "write_shader_sources", "files", List.of(
            Map.of("path", "shaders/a.glsl", "source", "const int A = 1;")), "reload", false),
         Map.of("type", "profile_frames", "frames", 10, "includeSamples", false)
      ));
      assertDoesNotThrow(() -> ViewfinderToolSchemas.validate(ViewfinderToolSchemas.input("run_actions"), valid, "batch"));

      Map<String, Object> unknownField = Map.of("actions", List.of(
         Map.of("type", "capture_frame", "frames", 1, "framez", 2)
      ));
      assertThrows(IllegalArgumentException.class, () -> ViewfinderToolSchemas.validate(
         ViewfinderToolSchemas.input("run_actions"), unknownField, "batch"));
   }

   @Test
   void renderSettingsAcceptPartialUpdatesAndRejectInvalidValues() {
      Map<String, Object> schema = ViewfinderToolSchemas.input("set_render_settings");
      for (Map<String, Object> arguments : List.<Map<String, Object>>of(
         Map.of("renderDistance", 2), Map.of("renderDistance", 32),
         Map.of("fov", 30), Map.of("fov", 110),
         Map.of("renderDistance", 12, "fov", 70))) {
         assertDoesNotThrow(() -> ViewfinderToolSchemas.validate(schema, arguments, "render settings"));
      }
      for (Map<String, Object> arguments : List.<Map<String, Object>>of(
         Map.of(), Map.of("renderDistance", 1), Map.of("renderDistance", 33),
         Map.of("fov", 29), Map.of("fov", 111), Map.of("fov", 70.5),
         Map.of("renderDistance", "12"), Map.of("renderDistance", 12, "fov", 111),
         Map.of("fov", 70, "unknown", true))) {
         assertThrows(IllegalArgumentException.class, () ->
            ViewfinderToolSchemas.validate(schema, arguments, "render settings"), arguments.toString());
      }
      assertDoesNotThrow(() -> ViewfinderToolSchemas.validate(
         ViewfinderToolSchemas.input("get_render_settings"), Map.of(), "render settings"));
      assertThrows(IllegalArgumentException.class, () -> ViewfinderToolSchemas.validate(
         ViewfinderToolSchemas.input("get_render_settings"), Map.of("fov", 70), "render settings"));
   }

   @Test
   void renderSettingsWorkInBatchesAndInvalidUpdatesFailPreflight() {
      Map<String, Object> schema = ViewfinderToolSchemas.input("run_actions");
      assertDoesNotThrow(() -> ViewfinderToolSchemas.validate(schema, Map.of("actions", List.of(
         Map.of("type", "get_render_settings"),
         Map.of("type", "set_render_settings", "renderDistance", 12, "fov", 70),
         Map.of("type", "wait_frames", "frames", 32),
         Map.of("type", "capture_frame", "frames", 0))), "batch"));
      for (Map<String, Object> invalid : List.<Map<String, Object>>of(
         Map.of("type", "set_render_settings"),
         Map.of("type", "set_render_settings", "renderDistance", 33),
         Map.of("type", "set_render_settings", "fov", 29))) {
         assertThrows(IllegalArgumentException.class, () -> ViewfinderToolSchemas.validate(schema,
            Map.of("actions", List.of(Map.of("type", "reload_shaders"), invalid)), "batch"));
      }
   }

   @Test
   void programBinaryIsExposedAsInlineAssemblyContent() {
      McpSchema.EmbeddedResource content = ViewfinderMcpServer.programAssemblyContent(
         "viewfinder://capture/test/program.bin", "!!NVpseudo assembly".getBytes(java.nio.charset.StandardCharsets.UTF_8));

      McpSchema.TextResourceContents resource = (McpSchema.TextResourceContents) content.resource();
      org.junit.jupiter.api.Assertions.assertEquals("text/x-nvidia-assembly", resource.mimeType());
      org.junit.jupiter.api.Assertions.assertEquals("!!NVpseudo assembly", resource.text());
   }
}
