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

import java.util.List;

/**
 * Builds the JavaScript prelude binding the {@code api} object a Code Mode script drives. Each exposed
 * tool becomes an {@code api.<tool>(input)} function delegating to the {@code rs.callTool} façade, so
 * every call keeps going through the regular pipeline (allow-list, secrets, output filters, audit,
 * tracing). The binding is generated from the very same list that feeds
 * {@link TypeScriptApiGenerator}, which guarantees the declared types and the runtime surface agree.
 * @author laurent
 */
public final class CodeModeScriptPrelude {

   private CodeModeScriptPrelude() {
      // Hide the default constructor of this utility class.
   }

   /**
    * Build the {@code api} binding prelude for the given tools.
    * <p>
    * The bindings are emitted as a single loop over a name array rather than one assignment per tool:
    * the per-tool cost drops from the ~110 characters of a full function declaration to just the quoted
    * name. This matters because the prelude is regenerated and recompiled on every {@code execute_code}
    * call, and Code Mode exists precisely to serve APIs with hundreds of operations.
    * @param tools The tools callable by the script.
    * @return The JavaScript prelude source, to be prepended to the submitted code.
    */
   public static String build(List<ExposedTool> tools) {
      StringBuilder builder = new StringBuilder("const api = {};\n");
      if (!tools.isEmpty()) {
         builder.append("for (const __rsTool of [");
         for (int i = 0; i < tools.size(); i++) {
            if (i > 0) {
               builder.append(',');
            }
            builder.append(jsString(tools.get(i).name()));
         }
         // `const` in a for-of gives a fresh binding per iteration, so each closure captures its own
         // tool name. Using `var` here would bind every function to the last name of the list.
         builder.append("]) {\n")
               .append("  api[__rsTool] = function (input) { return rs.callTool(__rsTool, ")
               .append("input === undefined || input === null ? {} : input); };\n")
               .append("}\n");
      }
      // Freezing prevents a script from shadowing a tool binding with its own implementation.
      builder.append("Object.freeze(api);\n");
      return builder.toString();
   }

   /** Render a Java string as a safely escaped JavaScript string literal. */
   static String jsString(String value) {
      StringBuilder builder = new StringBuilder("\"");
      for (int i = 0; i < value.length(); i++) {
         char c = value.charAt(i);
         switch (c) {
            case '"' -> builder.append("\\\"");
            case '\\' -> builder.append("\\\\");
            case '\n' -> builder.append("\\n");
            case '\r' -> builder.append("\\r");
            case '\t' -> builder.append("\\t");
            default -> {
               if (c < 0x20 || c == '\u2028' || c == '\u2029') {
                  builder.append(String.format("\\u%04x", (int) c));
               } else {
                  builder.append(c);
               }
            }
         }
      }
      return builder.append('"').toString();
   }
}
