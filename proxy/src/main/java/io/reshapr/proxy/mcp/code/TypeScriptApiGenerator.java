/*
 * Copyright The Reshapr Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.reshapr.proxy.mcp.code;

import io.reshapr.proxy.mcp.McpSchema;

import jakarta.annotation.Nullable;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Generates the TypeScript declaration of the API a Code Mode script may drive. The declarations are
 * derived from the MCP input schemas of the exposed tools, which the protocol converters already
 * produce from OpenAPI / GraphQL / Protobuf — so a single generator covers every service type.
 * <p>
 * The output is purely informational: it is returned by the {@code get_api_types} meta-tool so the LLM
 * knows the exact shape of the {@code api.<tool>(input)} calls it can write. The runtime binding is
 * produced separately by {@link CodeModeScriptPrelude}.
 * @author laurent
 */
public final class TypeScriptApiGenerator {

   /** Identifiers that can be emitted as a bare TypeScript member name. */
   private static final Pattern SAFE_IDENTIFIER = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*");

   /** Maximum nesting depth rendered before collapsing to {@code any} (guards against cyclic schemas). */
   private static final int MAX_DEPTH = 6;

   /** The preamble describing the sandbox contract, shared by every generated declaration. */
   static final String PREAMBLE = """
         // Reshapr MCP Code Mode — TypeScript surface of this exposition.
         // Write the body of a JavaScript function and `return` the (small) final answer.
         // Every call below is synchronous and returns a ToolResult; nothing reaches the model unless
         // you return it, so filter and aggregate here instead of returning raw payloads.

         type ToolResult<T = any> = {
           ok: boolean;                                    // false when the tool or the backend failed
           content: T | null;                              // parsed JSON payload of the tool response
           error: { code: number; message: string } | null;
         };

         declare const rs: {
           /** Call a tool of this exposition by name (same as api.<tool>(input)). */
           callTool(tool: string, input?: object): ToolResult;
           /** Start a tool call without blocking; collect the results with rs.awaitPromises. */
           callToolAsync(tool: string, input?: object): Promise<ToolResult>;
           /** Await several calls started with rs.callToolAsync, in order. */
           awaitPromises(...promises: Promise<ToolResult>[]): ToolResult[];
           /** Abort the script with a structured error surfaced to the MCP client. */
           fail(message: string, data?: unknown): never;
         };
         """;

   private TypeScriptApiGenerator() {
      // Hide the default constructor of this utility class.
   }

   /**
    * Generate the TypeScript declaration of the given tools.
    * @param tools The tools to declare, in advertisement order.
    * @return The TypeScript source, ready to be returned to the MCP client.
    */
   public static String generate(List<ExposedTool> tools) {
      StringBuilder builder = new StringBuilder(PREAMBLE);
      builder.append("\ndeclare const api: {\n");
      if (tools.isEmpty()) {
         builder.append("  // No tool is exposed by this configuration plan.\n");
      }
      for (ExposedTool tool : tools) {
         appendTool(builder, tool);
      }
      builder.append("};\n");
      return builder.toString();
   }

   /** Append the JSDoc comment and signature of a single tool. */
   private static void appendTool(StringBuilder builder, ExposedTool tool) {
      String description = tool.safeDescription();
      if (!description.isBlank()) {
         builder.append("  /** ").append(singleLine(description)).append(" */\n");
      }
      builder.append("  ").append(memberName(tool.name()))
            .append("(input: ").append(renderInput(tool.inputSchema()))
            .append("): ToolResult;\n");
   }

   /** Render the input parameter type of a tool from its MCP input schema. */
   private static String renderInput(@Nullable McpSchema.JsonSchema schema) {
      if (schema == null || schema.properties() == null || schema.properties().isEmpty()) {
         return "Record<string, never>";
      }
      Set<String> required = schema.required() != null ? Set.copyOf(schema.required()) : Set.of();
      return renderObject(schema.properties(), required, 1);
   }

   /** Render an object type literal from a JSON schema {@code properties} map. */
   private static String renderObject(Map<String, Object> properties, Set<String> required, int depth) {
      if (properties.isEmpty()) {
         return "Record<string, any>";
      }
      String indent = "  ".repeat(depth + 1);
      StringBuilder builder = new StringBuilder("{\n");
      for (Map.Entry<String, Object> property : properties.entrySet()) {
         String propertyDescription = stringField(property.getValue(), "description");
         if (propertyDescription != null && !propertyDescription.isBlank()) {
            builder.append(indent).append("/** ").append(singleLine(propertyDescription)).append(" */\n");
         }
         builder.append(indent).append(memberName(property.getKey()))
               .append(required.contains(property.getKey()) ? "" : "?")
               .append(": ").append(renderType(property.getValue(), depth + 1)).append(";\n");
      }
      builder.append("  ".repeat(depth)).append("}");
      return builder.toString();
   }

   /** Render the TypeScript type of a single JSON schema node. */
   @SuppressWarnings("unchecked")
   private static String renderType(@Nullable Object node, int depth) {
      if (depth > MAX_DEPTH || !(node instanceof Map<?, ?> rawMap)) {
         return "any";
      }
      Map<String, Object> map = (Map<String, Object>) rawMap;

      // Enumerations become a union of literals, whatever their declared type.
      if (map.get("enum") instanceof Collection<?> values && !values.isEmpty()) {
         return values.stream().map(TypeScriptApiGenerator::renderLiteral).reduce((a, b) -> a + " | " + b).orElse("any");
      }
      // oneOf / anyOf become a union of the rendered branches.
      for (String unionKey : List.of("oneOf", "anyOf")) {
         if (map.get(unionKey) instanceof Collection<?> branches && !branches.isEmpty()) {
            return branches.stream().map(branch -> renderType(branch, depth + 1)).distinct()
                  .reduce((a, b) -> a + " | " + b).orElse("any");
         }
      }

      String type = stringField(map, "type");
      if (type == null) {
         return map.containsKey("properties") ? renderObject(childProperties(map), requiredOf(map), depth) : "any";
      }
      return switch (type) {
         case "string" -> "string";
         case "integer", "number" -> "number";
         case "boolean" -> "boolean";
         case "null" -> "null";
         case "array" -> renderArray(map, depth);
         case "object" -> map.containsKey("properties")
               ? renderObject(childProperties(map), requiredOf(map), depth)
               : "Record<string, any>";
         default -> "any";
      };
   }

   /** Render an array type, parenthesizing union item types so {@code (A | B)[]} stays correct. */
   private static String renderArray(Map<String, Object> map, int depth) {
      String itemType = renderType(map.get("items"), depth + 1);
      boolean needsParentheses = itemType.contains("|") || itemType.contains("{");
      return needsParentheses ? "(" + itemType + ")[]" : itemType + "[]";
   }

   /** Extract the {@code properties} child map of a schema node. */
   @SuppressWarnings("unchecked")
   private static Map<String, Object> childProperties(Map<String, Object> map) {
      return map.get("properties") instanceof Map<?, ?> properties
            ? (Map<String, Object>) properties : Map.of();
   }

   /** Extract the {@code required} child list of a schema node. */
   private static Set<String> requiredOf(Map<String, Object> map) {
      if (map.get("required") instanceof Collection<?> required) {
         return required.stream().filter(String.class::isInstance).map(String.class::cast)
               .collect(java.util.stream.Collectors.toUnmodifiableSet());
      }
      return Set.of();
   }

   /** Render a JSON scalar as a TypeScript literal type. */
   private static String renderLiteral(@Nullable Object value) {
      if (value == null) {
         return "null";
      }
      if (value instanceof Number || value instanceof Boolean) {
         return value.toString();
      }
      return "\"" + value.toString().replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
   }

   /** Read a string field of a (possibly non-map) schema node, or {@code null}. */
   @Nullable
   private static String stringField(@Nullable Object node, String field) {
      if (node instanceof Map<?, ?> map && map.get(field) instanceof String value) {
         return value;
      }
      return null;
   }

   /**
    * Render a tool or property name as a TypeScript member: bare when it is a valid identifier,
    * quoted otherwise (MCP tool names may legitimately contain dashes or dots).
    */
   static String memberName(String name) {
      if (SAFE_IDENTIFIER.matcher(name).matches()) {
         return name;
      }
      return "\"" + name.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
   }

   /** Collapse a description to a single comment-safe line. */
   private static String singleLine(String text) {
      return text.replace("*/", "*\\/").replaceAll("\\s+", " ").strip();
   }
}
