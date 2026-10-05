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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Test case for {@link ExposedTool}. The record is trivial but {@code safeDescription()} is relied upon by
 * both the search ranking and the TypeScript generator, which must never see a {@code null} description.
 * @author laurent
 */
class ExposedToolTest {

   @Test
   void testSafeDescriptionReturnsTheDescriptionWhenPresent() {
      ExposedTool tool = new ExposedTool("getIssue", "Get a single issue.", null);

      assertEquals("Get a single issue.", tool.safeDescription());
   }

   @Test
   void testSafeDescriptionReturnsAnEmptyStringWhenAbsent() {
      ExposedTool tool = new ExposedTool("getIssue", null, null);

      assertNull(tool.description());
      assertNotNull(tool.safeDescription());
      assertEquals("", tool.safeDescription());
   }

   @Test
   void testComponentsAreCarriedAsIs() {
      McpSchema.JsonSchema schema = new McpSchema.JsonSchema("object",
            Map.of("owner", Map.of("type", "string")), List.of("owner"), false);
      ExposedTool tool = new ExposedTool("getIssue", "desc", schema);

      assertEquals("getIssue", tool.name());
      assertEquals("desc", tool.description());
      assertEquals(schema, tool.inputSchema());
   }
}
