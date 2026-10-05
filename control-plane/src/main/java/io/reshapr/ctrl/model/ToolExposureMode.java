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
package io.reshapr.ctrl.model;

/**
 * How a {@link ConfigurationPlan} exposes the operations of its service to MCP clients.
 * @author laurent
 */
public enum ToolExposureMode {

   /** Default: every exposed operation is advertised as its own MCP tool (classic function calling). */
   TOOLS,

   /**
    * MCP Code Mode: {@code tools/list} only advertises the {@code search_tools}, {@code get_api_types} and
    * {@code execute_code} meta-tools. The client (LLM) discovers the API surface on demand and drives it by
    * writing JavaScript executed in the gateway sandbox.
    */
   CODE,

   /** Both the native tools and the Code Mode meta-tools are advertised. Useful during a transition. */
   HYBRID;

   /** The mode applied when a configuration plan declares none. */
   public static final ToolExposureMode DEFAULT = TOOLS;
}
