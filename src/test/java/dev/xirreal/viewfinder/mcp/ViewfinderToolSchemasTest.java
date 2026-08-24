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
   void programBinaryIsExposedAsInlineAssemblyContent() {
      McpSchema.EmbeddedResource content = ViewfinderMcpServer.programAssemblyContent(
         "viewfinder://capture/test/program.bin", "!!NVpseudo assembly".getBytes(java.nio.charset.StandardCharsets.UTF_8));

      McpSchema.TextResourceContents resource = (McpSchema.TextResourceContents) content.resource();
      org.junit.jupiter.api.Assertions.assertEquals("text/x-nvidia-assembly", resource.mimeType());
      org.junit.jupiter.api.Assertions.assertEquals("!!NVpseudo assembly", resource.text());
   }
}
