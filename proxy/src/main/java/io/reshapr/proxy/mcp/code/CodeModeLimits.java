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

/**
 * The guard-rails applied to MCP Code Mode. Unlike a Scripted Custom Tool, the code run here is authored
 * by the model rather than by an administrator, so every dimension is bounded: how long it runs, how much
 * memory it may allocate, how many tool calls it may issue, and how much it may submit and return.
 *
 * @param timeoutMillis  Maximum wall-clock execution time of a snippet, in milliseconds.
 * @param maxToolCalls   Maximum number of tool calls a single snippet may issue.
 * @param maxDepth       Maximum script nesting depth, shared with the Scripted Custom Tools sandbox.
 * @param maxMemoryPages Maximum number of 64 KiB WebAssembly pages the QuickJS engine may grow to.
 * @param maxCodeSize    Maximum size, in characters, of a submitted snippet.
 * @param maxResultSize  Maximum size, in characters, of the value a snippet returns.
 * @author laurent
 */
public record CodeModeLimits(long timeoutMillis, int maxToolCalls, int maxDepth, int maxMemoryPages,
                             int maxCodeSize, int maxResultSize) {
}
