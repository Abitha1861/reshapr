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

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Test case for {@link TypeScriptApiGenerator}.
 * @author laurent
 */
class TypeScriptApiGeneratorTest {

   @Test
   void testGenerateRendersRequiredAndOptionalProperties() {
      McpSchema.JsonSchema schema = new McpSchema.JsonSchema("object", Map.of(
            "owner", Map.of("type", "string", "description", "The repository owner.")), List.of("owner"), false);
      ExposedTool tool = new ExposedTool("listIssues", "List the issues.", schema);

      String generated = TypeScriptApiGenerator.generate(List.of(tool));

      assertTrue(generated.contains("/** List the issues. */"));
      assertTrue(generated.contains("/** The repository owner. */"));
      assertTrue(generated.contains("owner: string;"));
      assertTrue(generated.contains("listIssues(input: {"));
      assertTrue(generated.contains("): ToolResult;"));
   }

   @Test
   void testGenerateMarksNonRequiredPropertiesOptional() {
      McpSchema.JsonSchema schema = new McpSchema.JsonSchema("object", Map.of(
            "state", Map.of("type", "string")), List.of(), false);

      String generated = TypeScriptApiGenerator.generate(List.of(new ExposedTool("listIssues", null, schema)));

      assertTrue(generated.contains("state?: string;"));
   }

   @Test
   void testGenerateRendersEnumsAsLiteralUnions() {
      McpSchema.JsonSchema schema = new McpSchema.JsonSchema("object", Map.of(
            "state", Map.of("type", "string", "enum", List.of("open", "closed"))), List.of("state"), false);

      String generated = TypeScriptApiGenerator.generate(List.of(new ExposedTool("listIssues", null, schema)));

      assertTrue(generated.contains("state: \"open\" | \"closed\";"));
   }

   @Test
   void testGenerateRendersArraysAndNestedObjects() {
      McpSchema.JsonSchema schema = new McpSchema.JsonSchema("object", Map.of(
            "labels", Map.of("type", "array", "items", Map.of("type", "string")),
            "author", Map.of("type", "object", "properties", Map.of("login", Map.of("type", "string")),
                  "required", List.of("login"))), List.of("labels"), false);

      String generated = TypeScriptApiGenerator.generate(List.of(new ExposedTool("createIssue", null, schema)));

      assertTrue(generated.contains("labels: string[];"));
      assertTrue(generated.contains("login: string;"));
      assertTrue(generated.contains("author?: {"));
   }

   @Test
   void testGenerateRendersNumbersBooleansAndUnknownTypes() {
      McpSchema.JsonSchema schema = new McpSchema.JsonSchema("object", Map.of(
            "page", Map.of("type", "integer"),
            "ratio", Map.of("type", "number"),
            "draft", Map.of("type", "boolean"),
            "payload", Map.of()), List.of(), false);

      String generated = TypeScriptApiGenerator.generate(List.of(new ExposedTool("search", null, schema)));

      assertTrue(generated.contains("page?: number;"));
      assertTrue(generated.contains("ratio?: number;"));
      assertTrue(generated.contains("draft?: boolean;"));
      assertTrue(generated.contains("payload?: any;"));
   }

   @Test
   void testGenerateQuotesNonIdentifierNames() {
      McpSchema.JsonSchema schema = new McpSchema.JsonSchema("object", Map.of(
            "per-page", Map.of("type", "integer")), List.of(), false);

      String generated = TypeScriptApiGenerator.generate(List.of(new ExposedTool("list-issues", null, schema)));

      assertTrue(generated.contains("\"list-issues\"(input: {"));
      assertTrue(generated.contains("\"per-page\"?: number;"));
   }

   @Test
   void testGenerateRendersEmptySchemaAsEmptyRecord() {
      String generated = TypeScriptApiGenerator.generate(List.of(new ExposedTool("ping", "Ping.", null)));

      assertTrue(generated.contains("ping(input: Record<string, never>): ToolResult;"));
   }

   @Test
   void testGenerateAlwaysCarriesTheSandboxContract() {
      String generated = TypeScriptApiGenerator.generate(List.of());

      assertTrue(generated.contains("type ToolResult"));
      assertTrue(generated.contains("declare const rs"));
      assertTrue(generated.contains("No tool is exposed by this configuration plan."));
   }

   @Test
   void testMemberNameQuotingRules() {
      assertEquals("listIssues", TypeScriptApiGenerator.memberName("listIssues"));
      assertEquals("$_ok1", TypeScriptApiGenerator.memberName("$_ok1"));
      assertEquals("\"list-issues\"", TypeScriptApiGenerator.memberName("list-issues"));
      assertEquals("\"1st\"", TypeScriptApiGenerator.memberName("1st"));
   }

   @Test
   void testMemberNameEscapesQuotesInsideTheQuotedForm() {
      assertEquals("\"we\\\"ird\"", TypeScriptApiGenerator.memberName("we\"ird"));
      assertEquals("\"back\\\\slash\"", TypeScriptApiGenerator.memberName("back\\slash"));
   }

   @Test
   void testGenerateRendersOneOfAndAnyOfAsUnions() {
      McpSchema.JsonSchema schema = new McpSchema.JsonSchema("object", Map.of(
            "id", Map.of("oneOf", List.of(Map.of("type", "string"), Map.of("type", "integer"))),
            "flag", Map.of("anyOf", List.of(Map.of("type", "boolean"), Map.of("type", "null")))),
            List.of("id"), false);

      String generated = TypeScriptApiGenerator.generate(List.of(new ExposedTool("get", null, schema)));

      assertTrue(generated.contains("id: string | number;"), generated);
      assertTrue(generated.contains("flag?: boolean | null;"), generated);
   }

   @Test
   void testGenerateDeduplicatesIdenticalUnionBranches() {
      McpSchema.JsonSchema schema = new McpSchema.JsonSchema("object", Map.of(
            "id", Map.of("oneOf", List.of(Map.of("type", "string"), Map.of("type", "string")))),
            List.of("id"), false);

      String generated = TypeScriptApiGenerator.generate(List.of(new ExposedTool("get", null, schema)));

      assertTrue(generated.contains("id: string;"), generated);
   }

   @Test
   void testGenerateParenthesizesArraysOfUnionsAndObjects() {
      McpSchema.JsonSchema schema = new McpSchema.JsonSchema("object", Map.of(
            "mixed", Map.of("type", "array", "items",
                  Map.of("oneOf", List.of(Map.of("type", "string"), Map.of("type", "integer")))),
            "people", Map.of("type", "array", "items",
                  Map.of("type", "object", "properties", Map.of("name", Map.of("type", "string"))))),
            List.of(), false);

      String generated = TypeScriptApiGenerator.generate(List.of(new ExposedTool("get", null, schema)));

      assertTrue(generated.contains("(string | number)[]"), generated);
      // An inline object literal must be parenthesized too, otherwise `{...}[]` would be an index access.
      assertTrue(generated.contains(")[]"), generated);
      assertTrue(generated.contains("name?: string;"), generated);
   }

   @Test
   void testGenerateRendersEnumsOfNonStringLiterals() {
      McpSchema.JsonSchema schema = new McpSchema.JsonSchema("object", Map.of(
            "level", Map.of("enum", java.util.Arrays.asList(1, true, null))), List.of("level"), false);

      String generated = TypeScriptApiGenerator.generate(List.of(new ExposedTool("set", null, schema)));

      assertTrue(generated.contains("level: 1 | true | null;"), generated);
   }

   @Test
   void testGenerateEscapesQuotesInEnumLiterals() {
      McpSchema.JsonSchema schema = new McpSchema.JsonSchema("object", Map.of(
            "q", Map.of("type", "string", "enum", List.of("a\"b"))), List.of("q"), false);

      String generated = TypeScriptApiGenerator.generate(List.of(new ExposedTool("search", null, schema)));

      assertTrue(generated.contains("q: \"a\\\"b\";"), generated);
   }

   @Test
   void testGenerateRendersObjectsWithoutPropertiesAsOpenRecords() {
      McpSchema.JsonSchema schema = new McpSchema.JsonSchema("object", Map.of(
            "metadata", Map.of("type", "object")), List.of(), false);

      String generated = TypeScriptApiGenerator.generate(List.of(new ExposedTool("get", null, schema)));

      assertTrue(generated.contains("metadata?: Record<string, any>;"), generated);
   }

   @Test
   void testGenerateInfersAnObjectFromPropertiesWithoutAType() {
      McpSchema.JsonSchema schema = new McpSchema.JsonSchema("object", Map.of(
            "author", Map.of("properties", Map.of("login", Map.of("type", "string")),
                  "required", List.of("login"))), List.of(), false);

      String generated = TypeScriptApiGenerator.generate(List.of(new ExposedTool("get", null, schema)));

      assertTrue(generated.contains("login: string;"), generated);
   }

   @Test
   void testGenerateRendersUnknownAndNullTypes() {
      McpSchema.JsonSchema schema = new McpSchema.JsonSchema("object", Map.of(
            "weird", Map.of("type", "fancy"),
            "nothing", Map.of("type", "null")), List.of(), false);

      String generated = TypeScriptApiGenerator.generate(List.of(new ExposedTool("get", null, schema)));

      assertTrue(generated.contains("weird?: any;"), generated);
      assertTrue(generated.contains("nothing?: null;"), generated);
   }

   @Test
   void testGenerateCollapsesTooDeeplyNestedSchemasToAny() {
      // Build 10 levels of nesting, well past the MAX_DEPTH guard that protects against cyclic schemas.
      Map<String, Object> node = Map.of("type", "string");
      for (int i = 0; i < 10; i++) {
         node = Map.of("type", "object", "properties", Map.of("child", node));
      }
      McpSchema.JsonSchema schema = new McpSchema.JsonSchema("object", Map.of("root", node), List.of(), false);

      String generated = TypeScriptApiGenerator.generate(List.of(new ExposedTool("deep", null, schema)));

      assertTrue(generated.contains("child?: any;"), generated);
   }

   @Test
   void testGenerateKeepsDescriptionsCommentSafeAndOnASingleLine() {
      McpSchema.JsonSchema schema = new McpSchema.JsonSchema("object", Map.of(
            "owner", Map.of("type", "string", "description", "Closes\nthe */ comment")), List.of(), false);

      String generated = TypeScriptApiGenerator.generate(
            List.of(new ExposedTool("listIssues", "Multi\n   line\tdescription", schema)));

      assertTrue(generated.contains("/** Multi line description */"), generated);
      // An embedded */ would terminate the JSDoc block early and break the whole declaration.
      assertTrue(generated.contains("/** Closes the *\\/ comment */"), generated);
   }

   @Test
   void testGenerateRendersSchemaWithoutPropertiesAsEmptyRecord() {
      McpSchema.JsonSchema schema = new McpSchema.JsonSchema("object", Map.of(), List.of(), false);

      String generated = TypeScriptApiGenerator.generate(List.of(new ExposedTool("ping", null, schema)));

      assertTrue(generated.contains("ping(input: Record<string, never>): ToolResult;"), generated);
   }

   @Test
   void testGenerateDeclaresEveryToolOfTheSurface() {
      McpSchema.JsonSchema schema = new McpSchema.JsonSchema("object", Map.of(), List.of(), false);
      List<ExposedTool> tools = List.of(new ExposedTool("a", null, schema),
            new ExposedTool("b", null, schema), new ExposedTool("c", null, schema));

      String generated = TypeScriptApiGenerator.generate(tools);

      for (ExposedTool tool : tools) {
         assertTrue(generated.contains(tool.name() + "(input:"), "missing " + tool.name());
      }
      assertTrue(generated.endsWith("};\n"));
   }
}

