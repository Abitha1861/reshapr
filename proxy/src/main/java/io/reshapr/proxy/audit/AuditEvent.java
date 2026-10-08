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
package io.reshapr.proxy.audit;

import jakarta.annotation.Nullable;

/**
 * Immutable record representing an MCP audit event. Serves as the contract between
 * instrumentation points (controllers) and the {@link AuditLogger}.
 *
 * @param method The MCP method invoked (e.g. "tools/call", "resources/read").
 * @param targetName The name of the tool or resource invoked, if applicable.
 * @param outcome The outcome of the call: "success" or "failure".
 * @param errorCode The JSONRPC error code, if the call failed.
 * @param durationMs Duration of the call in milliseconds.
 * @param serviceName The service name.
 * @param serviceVersion The service version.
 * @param organizationId The organization ID.
 * @param requestId The JSONRPC request ID.
 * @param sessionId The MCP session ID, if available.
 * @param sourceIp The remote IP address of the caller.
 * @param userId The authenticated user ID (JWT subject), if available.
 * @param codeMode The Code Mode execution details, if the call was an {@code execute_code} one.
 * @author laurent
 */
public record AuditEvent(
      String method,
      @Nullable String targetName,
      String outcome,
      @Nullable Integer errorCode,
      long durationMs,
      String serviceName,
      String serviceVersion,
      String organizationId,
      @Nullable Object requestId,
      @Nullable String sessionId,
      @Nullable String sourceIp,
      @Nullable String userId,
      long responseSize,
      @Nullable String traceId,
      @Nullable CodeModeInfo codeMode
) {
   /** Convenience constants for outcome values. */
   public static final String OUTCOME_SUCCESS = "success";
   public static final String OUTCOME_FAILURE = "failure";

   /** Build an audit event for a call that is not a Code Mode execution. */
   public AuditEvent(String method, @Nullable String targetName, String outcome, @Nullable Integer errorCode,
                     long durationMs, String serviceName, String serviceVersion, String organizationId,
                     @Nullable Object requestId, @Nullable String sessionId, @Nullable String sourceIp,
                     @Nullable String userId, long responseSize, @Nullable String traceId) {
      this(method, targetName, outcome, errorCode, durationMs, serviceName, serviceVersion, organizationId,
            requestId, sessionId, sourceIp, userId, responseSize, traceId, null);
   }

   /**
    * The model-authored snippet an {@code execute_code} call carried.
    * The {@code hash} is what links this record to the gateway application log, which always names the
    * snippet by its digest but only carries the body when auditing is on.
    * @param hash A short, stable digest of the submitted code.
    * @param code The submitted code itself, or {@code null} when only the digest is retained.
    */
   public record CodeModeInfo(String hash, @Nullable String code) {
   }
}
