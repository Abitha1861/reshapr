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

import io.reshapr.proxy.mcp.DeclaredTool;
import io.reshapr.proxy.mcp.McpSchema;
import io.reshapr.proxy.mcp.ToolCallExecutor;
import io.reshapr.proxy.mcp.converters.McpToolConverter;
import io.reshapr.proxy.mcp.script.CustomToolScriptRunner;
import io.reshapr.proxy.mcp.script.ReshaprToolsBuiltins;
import io.reshapr.proxy.mcp.script.ScriptExecutionContext;
import io.reshapr.proxy.registry.ExpositionEntry;
import io.reshapr.proxy.registry.GatewayRegistry;
import io.reshapr.proxy.registry.OperationEntry;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Nullable;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Serves the three MCP Code Mode meta-tools for a single exposition: {@code search_tools} (progressive
 * disclosure over the exposed API surface), {@code get_api_types} (TypeScript declaration of that surface)
 * and {@code execute_code} (sandboxed execution of a model-authored snippet).
 * <p>
 * Code Mode deliberately reuses the Scripted Custom Tools machinery: the snippet runs in the same QuickJS4J
 * sandbox, and every {@code api.<tool>(...)} call goes back through {@link ToolCallExecutor}, so backend
 * secrets, output filters, auditing and tracing keep applying exactly as for a regular tool call. The
 * differences are the trust level and therefore the guard-rails: the allow-list is derived from the
 * configuration plan instead of being declared by the script, and {@link CodeModeLimits} bounds the code
 * size, the result size, the memory and the number of calls.
 * @author laurent
 */
public class CodeModeExecutor {

   /** Get a JBoss logging logger. */
   private static final Logger logger = Logger.getLogger(CodeModeExecutor.class);

   private final ExpositionEntry exposition;
   private final McpToolConverter converter;
   private final ToolCallExecutor toolCallExecutor;
   private final GatewayRegistry gatewayRegistry;
   private final CodeModeLimits limits;
   private final ObjectMapper mapper;

   /** Lazily resolved exposed API surface, shared by the three meta-tools within one request. */
   @Nullable
   private List<ExposedTool> exposedTools;

   /**
    * Build a CodeModeExecutor bound to an exposition.
    * @param exposition The exposition serving the Code Mode meta-tools.
    * @param converter The MCP tool converter resolving the exposed API surface of that exposition.
    * @param toolCallExecutor The executor performing the tool calls a snippet issues.
    * @param gatewayRegistry The registry, required by the script host API.
    * @param limits The Code Mode guard-rails.
    * @param mapper The object mapper used to serialize the meta-tool responses.
    */
   public CodeModeExecutor(ExpositionEntry exposition, McpToolConverter converter,
                           ToolCallExecutor toolCallExecutor, GatewayRegistry gatewayRegistry,
                           CodeModeLimits limits, ObjectMapper mapper) {
      this.exposition = exposition;
      this.converter = converter;
      this.toolCallExecutor = toolCallExecutor;
      this.gatewayRegistry = gatewayRegistry;
      this.limits = limits;
      this.mapper = mapper;
   }

   /**
    * The tools exposed by the configuration plan, flattened for Code Mode. This is the exact surface the
    * client would see in {@code tools} mode, which keeps Code Mode from widening the exposed API.
    * @return The exposed tools, in advertisement order.
    */
   public List<ExposedTool> exposedTools() {
      if (exposedTools == null) {
         List<OperationEntry> operations =
               converter.getExposedOperations(exposition.service(), exposition.configuration());
         List<ExposedTool> tools = new ArrayList<>(operations.size());
         for (OperationEntry operation : operations) {
            tools.add(new ExposedTool(converter.getToolName(operation), converter.getToolDescription(operation),
                  converter.getInputSchema(operation)));
         }
         exposedTools = List.copyOf(tools);
      }
      return exposedTools;
   }

   /** The Code Mode meta-tool definitions to advertise in {@code tools/list}. */
   public List<McpSchema.Tool> metaToolDefinitions() {
      return CodeModeTools.definitions(exposedTools().size());
   }

   /**
    * Dispatch a Code Mode meta-tool call.
    * @param toolName The meta-tool name, one of {@link CodeModeTools#META_TOOL_NAMES}.
    * @param arguments The tool call arguments.
    * @param headers The protocol-level headers to propagate to the tool calls a snippet issues.
    * @return The outcome of the meta-tool call.
    */
   public ToolCallExecutor.ToolCallOutcome call(String toolName, @Nullable Map<String, Object> arguments,
                                                Map<String, List<String>> headers) {
      Map<String, Object> args = arguments != null ? arguments : Map.of();
      return switch (toolName) {
         case CodeModeTools.SEARCH_TOOLS -> searchTools(args);
         case CodeModeTools.GET_API_TYPES -> getApiTypes(args);
         case CodeModeTools.EXECUTE_CODE -> executeCode(args, headers);
         default -> new ToolCallExecutor.Failure(McpSchema.ErrorCodes.INVALID_PARAMS,
               "Unknown Code Mode tool: " + toolName, null);
      };
   }

   /** Serve {@code search_tools}: rank the exposed tools against the query and return the best matches. */
   protected ToolCallExecutor.ToolCallOutcome searchTools(Map<String, Object> arguments) {
      String query = stringArgument(arguments.get("query"));
      String detail = stringArgument(arguments.get("detail"));
      if (detail == null || detail.isBlank()) {
         detail = CodeModeTools.DETAIL_SUMMARY;
      }
      int limit = intArgument(arguments.get("limit"), CodeModeTools.DEFAULT_SEARCH_LIMIT);
      limit = Math.clamp(limit, 1, CodeModeTools.MAX_SEARCH_LIMIT);

      List<ExposedTool> matches = rank(exposedTools(), query);
      int total = matches.size();
      List<ExposedTool> page = matches.size() > limit ? matches.subList(0, limit) : matches;

      List<Map<String, Object>> rendered = new ArrayList<>(page.size());
      for (ExposedTool tool : page) {
         Map<String, Object> entry = new LinkedHashMap<>();
         entry.put("name", tool.name());
         if (!CodeModeTools.DETAIL_NAME.equals(detail)) {
            entry.put("description", tool.safeDescription());
         }
         if (CodeModeTools.DETAIL_SCHEMA.equals(detail)) {
            entry.put("inputSchema", tool.inputSchema());
         }
         rendered.add(entry);
      }

      Map<String, Object> result = new LinkedHashMap<>();
      result.put("tools", rendered);
      result.put("returned", rendered.size());
      result.put("matched", total);
      result.put("exposed", exposedTools().size());
      if (total > rendered.size()) {
         result.put("hint", "More tools match this query: refine it or raise 'limit'.");
      }
      return success(result);
   }

   /** Serve {@code get_api_types}: generate the TypeScript declaration of the requested tools. */
   protected ToolCallExecutor.ToolCallOutcome getApiTypes(Map<String, Object> arguments) {
      List<String> requested = stringListArgument(arguments.get("tools"));
      List<ExposedTool> tools = exposedTools();
      if (!requested.isEmpty()) {
         List<String> unknown = new ArrayList<>();
         List<ExposedTool> selected = new ArrayList<>(requested.size());
         for (String name : requested) {
            tools.stream().filter(tool -> tool.name().equals(name)).findFirst()
                  .ifPresentOrElse(selected::add, () -> unknown.add(name));
         }
         if (!unknown.isEmpty()) {
            return new ToolCallExecutor.Failure(McpSchema.ErrorCodes.INVALID_PARAMS,
                  "Unknown tool(s): " + String.join(", ", unknown) + ". Use search_tools to find valid names.",
                  null);
         }
         tools = selected;
      }
      return new ToolCallExecutor.Success(TypeScriptApiGenerator.generate(tools), false);
   }

   /** Serve {@code execute_code}: run the submitted snippet in the sandbox, bounded by the Code Mode limits. */
   protected ToolCallExecutor.ToolCallOutcome executeCode(Map<String, Object> arguments, Map<String, List<String>> headers) {
      String code = stringArgument(arguments.get("code"));
      if (code == null || code.isBlank()) {
         return new ToolCallExecutor.Failure(McpSchema.ErrorCodes.INVALID_PARAMS,
               "The 'code' argument is required and must be a non-empty JavaScript function body.", null);
      }
      if (code.length() > limits.maxCodeSize()) {
         return new ToolCallExecutor.Failure(McpSchema.ErrorCodes.INVALID_PARAMS,
               "Submitted code exceeds the maximum size of " + limits.maxCodeSize()
                     + " characters. Split the work into several execute_code calls.", null);
      }

      // Guard-rail: a snippet must not be able to re-enter the sandbox through a scripted custom tool chain.
      int parentDepth = ScriptExecutionContext.currentDepth();
      if (parentDepth >= limits.maxDepth()) {
         return new ToolCallExecutor.Failure(McpSchema.ErrorCodes.INVALID_REQUEST,
               "Maximum script nesting depth reached", null);
      }

      List<ExposedTool> tools = exposedTools();
      if (exposition.configuration().audit()) {
         logger.infof("Code Mode execute_code on exposition '%s' (service '%s'): %d chars, %d tools allowed%n%s",
               exposition.id(), exposition.service().name(), code.length(), tools.size(), code);
      } else {
         logger.debugf("Code Mode execute_code on exposition '%s': %d chars", exposition.id(), code.length());
      }

      // The allow-list is derived from the configuration plan rather than declared by the script: Code Mode
      // can never reach an operation the plan reshaped away, nor a tool of another service.
      List<DeclaredTool> allowedTools = tools.stream()
            .map(tool -> new DeclaredTool(null, tool.name())).toList();

      ReshaprToolsBuiltins builtins = new ReshaprToolsBuiltins(exposition, gatewayRegistry, toolCallExecutor,
            headers, allowedTools, limits.maxToolCalls());
      CustomToolScriptRunner runner = new CustomToolScriptRunner(mapper, limits.timeoutMillis(),
            CodeModeScriptPrelude.build(tools), limits.maxMemoryPages());

      try {
         String result = runner.run(code, Map.of(), builtins, parentDepth + 1);
         if (result != null && result.length() > limits.maxResultSize()) {
            logger.warnf("Code Mode result of %d characters exceeds the %d characters budget",
                  result.length(), limits.maxResultSize());
            return new ToolCallExecutor.Success("The script returned " + result.length() + " characters, over the "
                  + limits.maxResultSize() + " characters budget. Reduce the result inside the script (select "
                  + "the fields you need, aggregate, or paginate) and run it again.", true, builtins.accumulatedAttrs());
         }
         return new ToolCallExecutor.Success(result, false, builtins.accumulatedAttrs());
      } catch (CustomToolScriptRunner.CustomToolScriptException e) {
         // Surface the failure as a tool error rather than a protocol error: the model is expected to read
         // the message and submit a corrected snippet.
         logger.warnf("Code Mode script failed: %s", e.getMessage());
         String errorContent = e.isCompilationError()
               ? "The submitted code failed to compile: " + e.errorContent()
               : e.errorContent();
         return new ToolCallExecutor.Success(errorContent, true, builtins.accumulatedAttrs());
      } catch (Exception e) {
         logger.errorf(e, "Exception while running a Code Mode script on exposition '%s'", exposition.id());
         return new ToolCallExecutor.Success("Script execution failed", true, builtins.accumulatedAttrs());
      }
   }

   /**
    * Rank the tools against a free-text query. A term found in the tool name weighs more than one found in
    * its description; a blank query keeps every tool in advertisement order.
    */
   private static List<ExposedTool> rank(List<ExposedTool> tools, @Nullable String query) {
      if (query == null || query.isBlank()) {
         return tools;
      }
      String[] terms = query.toLowerCase(Locale.ROOT).split("[^a-z0-9]+");
      record Scored(ExposedTool tool, int score, int order) {
      }
      List<Scored> scored = new ArrayList<>();
      for (int i = 0; i < tools.size(); i++) {
         ExposedTool tool = tools.get(i);
         String name = tool.name().toLowerCase(Locale.ROOT);
         String description = tool.safeDescription().toLowerCase(Locale.ROOT);
         int score = 0;
         for (String term : terms) {
            if (term.isEmpty()) {
               continue;
            }
            if (name.contains(term)) {
               score += 3;
            }
            if (description.contains(term)) {
               score += 1;
            }
         }
         if (score > 0) {
            scored.add(new Scored(tool, score, i));
         }
      }
      return scored.stream()
            .sorted(Comparator.comparingInt(Scored::score).reversed().thenComparingInt(Scored::order))
            .map(Scored::tool).toList();
   }

   /** Serialize a structured meta-tool result as the JSON text content of a successful tool call. */
   private ToolCallExecutor.ToolCallOutcome success(Object result) {
      try {
         return new ToolCallExecutor.Success(mapper.writeValueAsString(result), false);
      } catch (Exception e) {
         logger.error("Cannot serialize the Code Mode meta-tool result", e);
         return new ToolCallExecutor.Failure(McpSchema.ErrorCodes.INTERNAL_ERROR,
               "Cannot serialize the Code Mode result", null);
      }
   }

   /** Read a string tool argument, tolerating a non-string value. */
   @Nullable
   private static String stringArgument(@Nullable Object value) {
      return value != null ? value.toString() : null;
   }

   /** Read an integer tool argument, falling back to the given default. */
   private static int intArgument(@Nullable Object value, int defaultValue) {
      if (value instanceof Number number) {
         return number.intValue();
      }
      if (value instanceof String text && !text.isBlank()) {
         try {
            return Integer.parseInt(text.trim());
         } catch (NumberFormatException e) {
            return defaultValue;
         }
      }
      return defaultValue;
   }

   /** Read a list-of-strings tool argument, tolerating a single string. */
   private static List<String> stringListArgument(@Nullable Object value) {
      if (value instanceof Collection<?> collection) {
         return collection.stream().filter(java.util.Objects::nonNull).map(Object::toString)
               .filter(name -> !name.isBlank()).toList();
      }
      if (value instanceof String text && !text.isBlank()) {
         return List.of(text);
      }
      return List.of();
   }
}
