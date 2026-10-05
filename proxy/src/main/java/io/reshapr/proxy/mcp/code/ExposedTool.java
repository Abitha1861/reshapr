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

/**
 * A tool of the exposed API surface, as seen by MCP Code Mode. It is the flattened form of an
 * {@link io.reshapr.proxy.registry.OperationEntry} resolved through the exposition's
 * {@link io.reshapr.proxy.mcp.converters.McpToolConverter}, so Code Mode never needs to know which
 * protocol (REST, GraphQL, gRPC) or reshaping (custom tools) produced it.
 *
 * @param name        The MCP tool name, as it must be passed to {@code rs.callTool}.
 * @param description The tool description, possibly {@code null}.
 * @param inputSchema The JSON schema of the tool arguments, possibly {@code null}.
 * @author laurent
 */
public record ExposedTool(String name, @Nullable String description, @Nullable McpSchema.JsonSchema inputSchema) {

   /** The description, never {@code null} (empty string when the tool declares none). */
   public String safeDescription() {
      return description != null ? description : "";
   }
}
