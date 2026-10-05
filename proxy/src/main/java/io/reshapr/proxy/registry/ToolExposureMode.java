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
package io.reshapr.proxy.registry;

import jakarta.annotation.Nullable;

/**
 * How a configuration plan exposes the operations of its service to MCP clients. Mirrors the
 * control-plane {@code io.reshapr.ctrl.model.ToolExposureMode} carried over the EDS gRPC contract.
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

   /**
    * Parse the wire representation of a tool exposure mode, falling back to {@link #DEFAULT} for a
    * blank or unknown value so an older/newer control plane never breaks the gateway.
    * @param value The raw mode name carried over the EDS contract.
    * @return The matching mode, or {@link #DEFAULT}.
    */
   public static ToolExposureMode fromValue(@Nullable String value) {
      if (value == null || value.isBlank()) {
         return DEFAULT;
      }
      try {
         return valueOf(value.trim().toUpperCase());
      } catch (IllegalArgumentException e) {
         return DEFAULT;
      }
   }

   /** Whether this mode advertises the Code Mode meta-tools. */
   public boolean exposesCodeMode() {
      return this == CODE || this == HYBRID;
   }

   /** Whether this mode advertises one MCP tool per exposed operation. */
   public boolean exposesNativeTools() {
      return this == TOOLS || this == HYBRID;
   }
}
