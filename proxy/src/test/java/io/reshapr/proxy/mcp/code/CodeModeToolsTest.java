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
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Test case for {@link CodeModeTools}. The three meta-tool definitions are the only thing an MCP client
 * sees in pure Code Mode, and their descriptions carry the guidance prompt, so this test pins both their
 * JSON-schema contract and the presence of the workflow instructions the model depends on.
 * @author laurent
 */
class CodeModeToolsTest {

   @Test
   void testMetaToolNameSets() {
      assertEquals(Set.of("search_tools", "get_api_types", "execute_code"), CodeModeTools.META_TOOL_NAMES);
      // Discovery tools are answered before any backend-secret elicitation, execute_code is not.
      assertEquals(Set.of("search_tools", "get_api_types"), CodeModeTools.DISCOVERY_TOOL_NAMES);
      assertTrue(CodeModeTools.META_TOOL_NAMES.containsAll(CodeModeTools.DISCOVERY_TOOL_NAMES));
      assertFalse(CodeModeTools.DISCOVERY_TOOL_NAMES.contains(CodeModeTools.EXECUTE_CODE));
   }

   @Test
   void testDefinitionsAreTheThreeMetaToolsInDiscoveryOrder() {
      List<McpSchema.Tool> definitions = CodeModeTools.definitions(42);

      assertEquals(3, definitions.size());
      assertEquals(List.of(CodeModeTools.SEARCH_TOOLS, CodeModeTools.GET_API_TYPES, CodeModeTools.EXECUTE_CODE),
            definitions.stream().map(McpSchema.Tool::name).toList());
   }

   @Test
   void testEveryDefinitionCarriesAnObjectInputSchema() {
      for (McpSchema.Tool tool : CodeModeTools.definitions(3)) {
         assertNotNull(tool.description(), tool.name() + " has no description");
         assertFalse(tool.description().isBlank(), tool.name() + " has a blank description");
         assertNotNull(tool.inputSchema(), tool.name() + " has no input schema");
         assertEquals("object", tool.inputSchema().type());
         assertNotNull(tool.inputSchema().properties());
      }
   }

   @Test
   void testSearchToolsAdvertisesTheNumberOfExposedOperations() {
      McpSchema.Tool searchTools = CodeModeTools.definitions(137).getFirst();

      // Quoting the real count tells the model up front how much there is to discover.
      assertTrue(searchTools.description().contains("137"),
            "Unexpected description: " + searchTools.description());
      assertTrue(searchTools.description().contains(CodeModeTools.GET_API_TYPES));
      assertTrue(searchTools.description().contains(CodeModeTools.EXECUTE_CODE));
   }

   @Test
   @SuppressWarnings("unchecked")
   void testSearchToolsDeclaresQueryDetailAndLimitAsOptional() {
      McpSchema.JsonSchema schema = CodeModeTools.definitions(1).getFirst().inputSchema();

      assertEquals(List.of("query", "detail", "limit"), List.copyOf(schema.properties().keySet()));
      assertTrue(schema.required().isEmpty(), "search_tools must be callable without any argument");

      Map<String, Object> detail = (Map<String, Object>) schema.properties().get("detail");
      assertEquals(List.of(CodeModeTools.DETAIL_NAME, CodeModeTools.DETAIL_SUMMARY, CodeModeTools.DETAIL_SCHEMA),
            detail.get("enum"));
   }

   @Test
   void testGetApiTypesDeclaresAnOptionalArrayOfToolNames() {
      McpSchema.JsonSchema schema = CodeModeTools.definitions(1).get(1).inputSchema();

      assertTrue(schema.properties().containsKey("tools"));
      assertTrue(schema.required().isEmpty(), "get_api_types must accept being called without a selection");
   }

   @Test
   void testExecuteCodeRequiresTheCodeArgument() {
      McpSchema.Tool executeCode = CodeModeTools.definitions(1).get(2);

      assertEquals(List.of("code"), executeCode.inputSchema().required());
      assertTrue(executeCode.inputSchema().properties().containsKey("code"));
   }

   @Test
   void testExecuteCodeDescriptionCarriesTheGuidancePrompt() {
      String description = CodeModeTools.definitions(1).get(2).description();

      // The model only learns the sandbox contract from this description: pin its load-bearing parts.
      assertTrue(description.contains("api.<tool>(input)"), "missing the api binding documentation");
      assertTrue(description.contains("rs.callTool"), "missing the rs façade documentation");
      assertTrue(description.contains("rs.awaitPromises"), "missing the concurrency documentation");
      assertTrue(description.contains("rs.fail"), "missing the failure documentation");
      assertTrue(description.contains("{ ok, content, error }"), "missing the ToolResult envelope");
      assertTrue(description.contains("return"), "missing the 'must return' rule");
      assertTrue(description.contains("no network"), "missing the sandbox restrictions");
   }

   @Test
   void testSearchLimitsAreSane() {
      assertTrue(CodeModeTools.DEFAULT_SEARCH_LIMIT > 0);
      assertTrue(CodeModeTools.MAX_SEARCH_LIMIT >= CodeModeTools.DEFAULT_SEARCH_LIMIT);
   }
}
