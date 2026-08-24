package dev.xirreal.viewfinder.mcp;

import io.modelcontextprotocol.json.McpJsonDefaults;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class ViewfinderToolSchemas {
   static final int MAX_ACTIONS = 64;

   private static final List<String> ACTION_TYPES = List.of(
      "get_diagnostics", "clear_diagnostics", "reload_shaders", "list_shaderpacks", "switch_shaderpack",
      "set_shader_options", "write_shader_source", "write_shader_sources", "wait_frames", "capture_frame",
      "profile_frames", "capture_pass_outputs", "list_programs", "inspect_program", "dump_program_binary",
      "list_textures", "inspect_texture", "dump_texture", "list_ssbos", "inspect_ssbo", "dump_ssbo",
      "set_scene", "control_ticks"
   );

   private ViewfinderToolSchemas() {}

   static List<String> actionTypes() {
      return ACTION_TYPES;
   }

   static String actionTypeDescription() {
      return String.join(", ", ACTION_TYPES);
   }

   static Map<String, Object> input(String tool) {
      return switch (tool) {
         case "get_mcp_status", "clear_diagnostics", "reload_shaders", "list_shaderpacks", "list_programs",
              "list_textures", "list_ssbos" -> object();
         case "run_actions" -> runActions();
         case "get_diagnostics" -> schema(
            booleanProperty("includeSamples", "Include raw GPU timing samples; summaries are always returned", false));
         case "switch_shaderpack" -> schema(
            stringProperty("name", "Exact installed shaderpack name", true),
            optionMapProperty("config", "Boolean, number, or string shader option values to apply after switching"),
            booleanProperty("resetConfig", "Reset unspecified options to pack defaults before applying config", false),
            required("name"));
         case "set_shader_options" -> shaderOptionsSchema();
         case "write_shader_source" -> schema(
            stringProperty("path", "Path relative to the shaderpack, beginning with shaders/", true),
            stringProperty("source", "Complete replacement source", false),
            booleanProperty("reload", "Reload once after writing", true),
            required("path", "source"));
         case "write_shader_sources" -> schema(
            filesProperty(),
            booleanProperty("reload", "Reload once after all files are written", true),
            required("files"));
         case "wait_frames" -> schema(
            integerProperty("frames", "Rendered frames to wait", 1, 600, 1),
            integerProperty("maxSeconds", "Wall-clock deadline", 5, 110, 45));
         case "capture_frame" -> schema(
            integerProperty("frames", "Frames to wait before capture", 0, 600, 1));
         case "profile_frames" -> schema(
            integerProperty("frames", "Frames to profile", 1, 600, 60),
            integerProperty("maxSeconds", "Wall-clock deadline; returns a partial profile when reached", 5, 110, 45),
            booleanProperty("includeSamples", "Include raw timing samples in addition to per-pass summaries", false));
         case "capture_pass_outputs" -> schema(
            stringProperty("pass", "Exact observed leaf name or full pipeline path", true),
            integerProperty("timeoutSeconds", "How long to wait for the pass", 1, 110, 15),
            required("pass"));
         case "inspect_program", "dump_program_binary" -> programSelector();
         case "inspect_texture" -> inspectTexture();
         case "dump_texture" -> dumpTexture();
         case "inspect_ssbo" -> schema(
            integerProperty("index", "Iris SSBO binding index", 0, null, null),
            integerProperty("bytes", "Preview byte count", 1, 64 * 1024, 256),
            required("index"));
         case "dump_ssbo" -> schema(
            integerProperty("index", "Iris SSBO binding index", 0, null, null), required("index"));
         case "set_scene" -> scene();
         case "control_ticks" -> schema(
            enumProperty("action", "freeze", "resume", "step"),
            integerProperty("ticks", "Ticks to step when action is step", 1, 600, 1),
            numberProperty("tickRate", "Optional positive server tick rate", 0.01),
            required("action"));
         default -> throw new IllegalArgumentException("Unknown tool contract: " + tool);
      };
   }

   static void validate(Map<String, Object> schema, Object value, String context) {
      var validation = McpJsonDefaults.getSchemaValidator().validate(schema, value);
      if (!validation.valid()) {
         throw new IllegalArgumentException(context + " arguments are invalid: " + validation.errorMessage());
      }
   }

   static void validateAction(String type, Map<String, Object> action, int index) {
      validate(action(type), action, "Action " + index + " (" + type + ")");
   }

   private static Map<String, Object> runActions() {
      List<Map<String, Object>> variants = ACTION_TYPES.stream().map(ViewfinderToolSchemas::action).toList();
      return schema(Map.entry("actions", Map.of(
         "type", "array",
         "description", "Ordered actions validated completely before action zero executes",
         "minItems", 1,
         "maxItems", MAX_ACTIONS,
         "items", Map.of("oneOf", variants)
      )), required("actions"));
   }

   @SuppressWarnings("unchecked")
   private static Map<String, Object> action(String type) {
      Map<String, Object> action = new LinkedHashMap<>(input(type));
      Map<String, Object> properties = new LinkedHashMap<>((Map<String, Object>) action.get("properties"));
      properties.put("type", Map.of("type", "string", "const", type));
      action.put("properties", properties);

      List<String> required = new ArrayList<>();
      Object existing = action.get("required");
      if (existing instanceof List<?> names) names.forEach(name -> required.add(String.valueOf(name)));
      required.add("type");
      action.put("required", required);
      if (action.get("minProperties") instanceof Number minimum) {
         action.put("minProperties", minimum.intValue() + 1);
      }
      return action;
   }

   private static Map<String, Object> shaderOptionsSchema() {
      Map<String, Object> schema = schema(
         optionMapProperty("options", "Option names mapped to boolean, number, or string values"),
         booleanProperty("reset", "Reset unspecified options to pack defaults before applying values", false),
         required("options"));
      schema.put("anyOf", List.of(
         Map.of("properties", Map.of("options", Map.of("minProperties", 1))),
         Map.of("properties", Map.of("reset", Map.of("const", true)), "required", List.of("reset"))));
      return schema;
   }

   private static Map<String, Object> programSelector() {
      Map<String, Object> schema = schema(
         stringProperty("name", "Preferred: exact Iris program name, stable across reloads", true),
         integerProperty("id", "OpenGL program id; use only when no Iris name exists", 1, null, null));
      exactlyOne(schema, "name", "id");
      return schema;
   }

   private static Map<String, Object> inspectTexture() {
      Map<String, Object> schema = schema(
         stringProperty("name", "Preferred: exact Iris texture name such as colortex0", true),
         integerProperty("id", "OpenGL texture id; use only when no Iris name exists", 1, null, null),
         integerProperty("samples", "Maximum sampled pixels", 1, 1_000_000, 4096),
         integerProperty("x", "Pixel x; requires y", 0, null, null),
         integerProperty("y", "Pixel y; requires x", 0, null, null),
         integerProperty("z", "Texture layer", 0, null, 0));
      exactlyOne(schema, "name", "id");
      schema.put("dependentRequired", Map.of("x", List.of("y"), "y", List.of("x")));
      return schema;
   }

   private static Map<String, Object> dumpTexture() {
      Map<String, Object> schema = schema(
         stringProperty("name", "Preferred: exact Iris texture name", true),
         integerProperty("id", "OpenGL texture id; use only when no Iris name exists", 1, null, null),
         booleanProperty("raw", "Write raw bytes instead of PNG", false));
      exactlyOne(schema, "name", "id");
      return schema;
   }

   private static Map<String, Object> scene() {
      Map<String, Object> schema = schema(
         numberProperty("x", "Player x", null), numberProperty("y", "Player y", null),
         numberProperty("z", "Player z", null), numberProperty("yaw", "Player yaw", null),
         numberProperty("pitch", "Player pitch", null),
         integerProperty("time", "World clock ticks", null, null, null),
         enumProperty("weather", "clear", "rain", "thunder"));
      schema.put("minProperties", 1);
      return schema;
   }

   private static void exactlyOne(Map<String, Object> schema, String first, String second) {
      schema.put("oneOf", List.of(
         Map.of("required", List.of(first)),
         Map.of("required", List.of(second))));
   }

   private static Map<String, Object> object() {
      return Map.of("type", "object", "additionalProperties", false, "properties", Map.of());
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

   private static Map.Entry<String, Object> stringProperty(String name, String description, boolean nonEmpty) {
      Map<String, Object> property = baseProperty("string", description);
      if (nonEmpty) property.put("minLength", 1);
      return Map.entry(name, property);
   }

   private static Map.Entry<String, Object> booleanProperty(String name, String description, boolean fallback) {
      Map<String, Object> property = baseProperty("boolean", description);
      property.put("default", fallback);
      return Map.entry(name, property);
   }

   private static Map.Entry<String, Object> integerProperty(String name, String description,
      Integer minimum, Integer maximum, Integer fallback) {
      Map<String, Object> property = baseProperty("integer", description);
      if (minimum != null) property.put("minimum", minimum);
      if (maximum != null) property.put("maximum", maximum);
      if (fallback != null) property.put("default", fallback);
      return Map.entry(name, property);
   }

   private static Map.Entry<String, Object> numberProperty(String name, String description, Double exclusiveMinimum) {
      Map<String, Object> property = baseProperty("number", description);
      if (exclusiveMinimum != null) property.put("exclusiveMinimum", exclusiveMinimum);
      return Map.entry(name, property);
   }

   private static Map.Entry<String, Object> enumProperty(String name, String... values) {
      return Map.entry(name, Map.of("type", "string", "enum", List.of(values)));
   }

   private static Map.Entry<String, Object> optionMapProperty(String name, String description) {
      Map<String, Object> property = baseProperty("object", description);
      property.put("additionalProperties", Map.of("anyOf", List.of(
         Map.of("type", "boolean"), Map.of("type", "number"), Map.of("type", "string"))));
      return Map.entry(name, property);
   }

   private static Map.Entry<String, Object> filesProperty() {
      return Map.entry("files", Map.of(
         "type", "array",
         "description", "Shader source replacements written before one optional reload",
         "minItems", 1,
         "maxItems", MAX_ACTIONS,
         "items", schema(
            stringProperty("path", "Path relative to the shaderpack, beginning with shaders/", true),
            stringProperty("source", "Complete replacement source", false),
            required("path", "source"))));
   }

   private static Map<String, Object> baseProperty(String type, String description) {
      Map<String, Object> property = new LinkedHashMap<>();
      property.put("type", type);
      property.put("description", description);
      return property;
   }

   private static Map.Entry<String, Object> required(String... names) {
      return Map.entry("required", List.of(names));
   }
}
