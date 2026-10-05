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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The definitions of the three MCP Code Mode meta-tools advertised in place of (or alongside) the native
 * tools of an exposition. Their descriptions carry the guidance prompt that teaches the model the Code
 * Mode workflow: <em>search, read the types, write one script</em>.
 * @author laurent
 */
public final class CodeModeTools {

   /** Name of the progressive-disclosure meta-tool searching the exposed API surface. */
   public static final String SEARCH_TOOLS = "search_tools";

   /** Name of the meta-tool returning the TypeScript declaration of the exposed API surface. */
   public static final String GET_API_TYPES = "get_api_types";

   /** Name of the meta-tool executing a JavaScript snippet in the gateway sandbox. */
   public static final String EXECUTE_CODE = "execute_code";

   /** Every Code Mode meta-tool name; these names shadow a native tool of the same name. */
   public static final Set<String> META_TOOL_NAMES = Set.of(SEARCH_TOOLS, GET_API_TYPES, EXECUTE_CODE);

   /**
    * The meta-tools resolvable without touching the backend ({@code search_tools}, {@code get_api_types}).
    * They are answered before any backend-secret elicitation pre-flight, so a model can always discover
    * the API surface even when the backend credentials are not resolved yet.
    */
   public static final Set<String> DISCOVERY_TOOL_NAMES = Set.of(SEARCH_TOOLS, GET_API_TYPES);

   /** Detail levels accepted by {@code search_tools}. */
   public static final String DETAIL_NAME = "name";
   public static final String DETAIL_SUMMARY = "summary";
   public static final String DETAIL_SCHEMA = "schema";

   /** Default and maximum number of results returned by {@code search_tools}. */
   public static final int DEFAULT_SEARCH_LIMIT = 10;
   public static final int MAX_SEARCH_LIMIT = 100;

   private CodeModeTools() {
      // Hide the default constructor of this utility class.
   }

   /**
    * Build the meta-tool definitions advertised by {@code tools/list}.
    * @param toolCount The number of tools exposed by the configuration plan, quoted in the descriptions.
    * @return The three meta-tool definitions, in discovery order.
    */
   public static List<McpSchema.Tool> definitions(int toolCount) {
      return List.of(searchToolsDefinition(toolCount), getApiTypesDefinition(), executeCodeDefinition());
   }

   /** The {@code search_tools} definition. */
   private static McpSchema.Tool searchToolsDefinition(int toolCount) {
      Map<String, Object> properties = new LinkedHashMap<>();
      properties.put("query", Map.of(
            "type", "string",
            "description", "Free-text search terms matched against the tool names and descriptions. "
                  + "Leave empty to list everything."));
      properties.put("detail", Map.of(
            "type", "string",
            "enum", List.of(DETAIL_NAME, DETAIL_SUMMARY, DETAIL_SCHEMA),
            "description", "How much to return per match: 'name' (names only), 'summary' (name and "
                  + "description, the default) or 'schema' (adds the full JSON input schema)."));
      properties.put("limit", Map.of(
            "type", "integer",
            "description", "Maximum number of matches to return. Defaults to " + DEFAULT_SEARCH_LIMIT
                  + ", capped at " + MAX_SEARCH_LIMIT + "."));

      String description = """
            Search the %d operations this API exposes, and return only the matching ones. This is the \
            entry point of the Code Mode workflow: search first with a short query to find the relevant \
            tools, then call get_api_types on them to obtain their exact TypeScript signature, then write \
            a single execute_code script that chains the calls. Loading every definition up front is \
            never necessary.""".formatted(toolCount);

      return new McpSchema.Tool(SEARCH_TOOLS, description,
            new McpSchema.JsonSchema("object", properties, List.of(), false), null);
   }

   /** The {@code get_api_types} definition. */
   private static McpSchema.Tool getApiTypesDefinition() {
      Map<String, Object> properties = new LinkedHashMap<>();
      properties.put("tools", Map.of(
            "type", "array",
            "items", Map.of("type", "string"),
            "description", "Names of the tools to declare, as returned by search_tools. Omit to get the "
                  + "whole API surface, which may be large."));

      String description = """
            Return the TypeScript declaration of the API an execute_code script can drive: the \
            api.<tool>(input) signatures, the shape of their inputs and the ToolResult envelope they \
            return. Always call this for the tools you intend to use before writing a script, so the \
            arguments you pass are the right ones.""";

      return new McpSchema.Tool(GET_API_TYPES, description,
            new McpSchema.JsonSchema("object", properties, List.of(), false), null);
   }

   /** The {@code execute_code} definition, carrying the bulk of the guidance prompt. */
   private static McpSchema.Tool executeCodeDefinition() {
      Map<String, Object> properties = new LinkedHashMap<>();
      properties.put("code", Map.of(
            "type", "string",
            "description", "The JavaScript function body to run. It must end with a `return` of the final, "
                  + "already-reduced answer."));

      String description = """
            Run a JavaScript snippet inside this gateway to drive the API. Use it instead of calling tools \
            one by one: loops, conditionals, joins and aggregations all happen here, in a single round-trip.

            The snippet is the body of a function, so it must `return` its result. Inside it you get:
              - api.<tool>(input) — one synchronous function per exposed tool;
              - rs.callTool(name, input), rs.callToolAsync(name, input) and rs.awaitPromises(...p) to fan \
            out concurrent calls;
              - rs.fail(message, data) to abort with a structured error.
            Every call returns { ok, content, error }: check `ok` before using `content`.

            Rules: no network, no filesystem, no require/import, no access to any tool outside this \
            exposition. Only what you `return` is sent back to the model, so filter the payloads down to \
            the fields you actually need and never return a whole backend response. Example:

              const res = api.listIssues({ owner: "acme", repo: "widgets", state: "open" });
              if (!res.ok) rs.fail("cannot list issues", res.error);
              return res.content.map(i => ({ id: i.id, title: i.title }));""";

      return new McpSchema.Tool(EXECUTE_CODE, description,
            new McpSchema.JsonSchema("object", properties, List.of("code"), false), null);
   }
}
