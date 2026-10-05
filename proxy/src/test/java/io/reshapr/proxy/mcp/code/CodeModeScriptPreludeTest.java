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

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Test case for {@link CodeModeScriptPrelude}. The prelude is concatenated with model-authored code, so a
 * tool name must never be able to escape its string literal, and the {@code api} object must be frozen.
 * @author laurent
 */
class CodeModeScriptPreludeTest {

   private static ExposedTool tool(String name) {
      return new ExposedTool(name, null, null);
   }

   @Test
   void testBuildDeclaresOneBindingPerTool() {
      String prelude = CodeModeScriptPrelude.build(List.of(tool("listIssues"), tool("getIssue")));

      assertTrue(prelude.startsWith("const api = {};"));
      // Bindings are emitted as a loop over a name array, so the per-tool cost is just the quoted name.
      assertTrue(prelude.contains("for (const __rsTool of [\"listIssues\",\"getIssue\"])"),
            "unexpected binding loop: " + prelude);
      assertTrue(prelude.contains("api[__rsTool] = function (input)"));
      assertTrue(prelude.contains("rs.callTool(__rsTool,"));
   }

   @Test
   void testBuildStaysCompactOnALargeApiSurface() {
      // The prelude is rebuilt and recompiled on every execute_code call, and Code Mode targets APIs
      // with hundreds of operations: the cost must scale with the names, not with a per-tool function.
      List<ExposedTool> tools = java.util.stream.IntStream.range(0, 500)
            .mapToObj(i -> tool("operation_number_" + i)).collect(java.util.stream.Collectors.toList());

      String prelude = CodeModeScriptPrelude.build(tools);

      assertTrue(prelude.length() < 15_000,
            "the prelude grew back to a per-tool declaration: " + prelude.length() + " chars");
   }

   @Test
   void testBuildFreezesTheApiObject() {
      // Freezing is what prevents a script from shadowing a binding with its own implementation.
      assertTrue(CodeModeScriptPrelude.build(List.of(tool("a"))).contains("Object.freeze(api);"));
   }

   @Test
   void testBuildDefaultsAMissingInputToAnEmptyObject() {
      String prelude = CodeModeScriptPrelude.build(List.of(tool("ping")));

      assertTrue(prelude.contains("input === undefined || input === null ? {} : input"));
   }

   @Test
   void testBuildWithoutAnyToolStillYieldsAValidPrelude() {
      String prelude = CodeModeScriptPrelude.build(List.of());

      assertEquals("const api = {};\nObject.freeze(api);\n", prelude);
   }

   @Test
   void testBuildOnlyBindsTheGivenTools() {
      String prelude = CodeModeScriptPrelude.build(List.of(tool("listIssues")));

      assertFalse(prelude.contains("deleteRepository"));
   }

   @Test
   void testToolNamesCannotBreakOutOfTheirStringLiteral() {
      // A hostile (or simply exotic) tool name must stay confined to its literal.
      String prelude = CodeModeScriptPrelude.build(List.of(tool("ev\"il\\; rs.fail('x')")));

      assertFalse(prelude.contains("ev\"il"), "the quote was not escaped: " + prelude);
      assertTrue(prelude.contains("ev\\\"il\\\\"), "unexpected escaping: " + prelude);
   }

   @Test
   void testJsStringEscapesControlAndLineSeparatorCharacters() {
      assertEquals("\"a\\nb\"", CodeModeScriptPrelude.jsString("a\nb"));
      assertEquals("\"a\\rb\"", CodeModeScriptPrelude.jsString("a\rb"));
      assertEquals("\"a\\tb\"", CodeModeScriptPrelude.jsString("a\tb"));
      assertEquals("\"a\\\\b\"", CodeModeScriptPrelude.jsString("a\\b"));
      assertEquals("\"a\\u0000b\"", CodeModeScriptPrelude.jsString("a\u0000b"));
      // U+2028/U+2029 terminate a line in JavaScript source even inside a string literal.
      assertEquals("\"a\\u2028b\"", CodeModeScriptPrelude.jsString("a\u2028b"));
      assertEquals("\"a\\u2029b\"", CodeModeScriptPrelude.jsString("a\u2029b"));
   }

   @Test
   void testJsStringLeavesOrdinaryCharactersUntouched() {
      assertEquals("\"listIssues\"", CodeModeScriptPrelude.jsString("listIssues"));
      assertEquals("\"GET /orders/{id}\"", CodeModeScriptPrelude.jsString("GET /orders/{id}"));
   }
}
