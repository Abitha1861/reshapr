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

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.logs.LogRecordBuilder;
import io.opentelemetry.api.logs.Severity;
import jakarta.annotation.Nullable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;

/**
 * CDI bean that emits structured OTEL LogRecords for MCP audit events.
 * <p>
 * Each audit log record carries the attribute {@code log.type=audit} so that downstream
 * OTEL Collector pipelines (or SaaS backends like Dash0) can filter/route them independently
 * from regular application logs.
 * <p>
 * The attribute names are chosen to be easily mappable to ECS (Elastic Common Schema)
 * via an OTEL Collector {@code transform} processor when needed.
 * <p>
 * When OpenTelemetry is not available (e.g. dev mode), audit events are silently dropped
 * and a warning is logged at startup.
 *
 * @author laurent
 */
@ApplicationScoped
public class AuditLogger {

   private static final Logger logger = Logger.getLogger(AuditLogger.class);

   private static final String INSTRUMENTATION_SCOPE_NAME = "io.reshapr.audit";

   // Attribute keys — reusable and type-safe.
   private static final AttributeKey<String> LOG_TYPE = AttributeKey.stringKey("log.type");

   private static final AttributeKey<String> EVENT_ACTION = AttributeKey.stringKey("event.action");
   private static final AttributeKey<String> EVENT_OUTCOME = AttributeKey.stringKey("event.outcome");
   private static final AttributeKey<Long> EVENT_DURATION = AttributeKey.longKey("event.duration");
   private static final AttributeKey<String> EVENT_REASON = AttributeKey.stringKey("event.reason");

   private static final AttributeKey<String> SERVICE_NAME = AttributeKey.stringKey("service.name");
   private static final AttributeKey<String> SERVICE_VERSION = AttributeKey.stringKey("service.version");
   private static final AttributeKey<String> ORGANIZATION_ID = AttributeKey.stringKey("organization.id");

   private static final AttributeKey<String> MCP_REQUEST_ID = AttributeKey.stringKey("mcp.request.id");
   private static final AttributeKey<String> MCP_SESSION_ID = AttributeKey.stringKey("mcp.session.id");
   private static final AttributeKey<String> MCP_TARGET_NAME = AttributeKey.stringKey("mcp.target.name");
   private static final AttributeKey<Long> MCP_ERROR_CODE = AttributeKey.longKey("mcp.error.code");
   private static final AttributeKey<Long> MCP_RESPONSE_SIZE = AttributeKey.longKey("mcp.response.size");

   /** Code Mode: the snippet an {@code execute_code} call carried, and its stable digest. */
   private static final AttributeKey<String> MCP_CODE = AttributeKey.stringKey("mcp.code");
   private static final AttributeKey<String> MCP_CODE_HASH = AttributeKey.stringKey("mcp.code.hash");

   private static final AttributeKey<String> SOURCE_IP = AttributeKey.stringKey("source.ip");
   private static final AttributeKey<String> USER_ID = AttributeKey.stringKey("user.id");

   private static final AttributeKey<String> TRACE_ID = AttributeKey.stringKey("trace.id");
   private static final AttributeKey<String> SERVICE_ID = AttributeKey.stringKey("service.id");

   private static final AttributeKey<Long> HTTP_STATUS = AttributeKey.longKey("http.response.status_code");

   @Nullable
   private final io.opentelemetry.api.logs.Logger otelLogger;

   @Inject
   public AuditLogger(Instance<OpenTelemetry> openTelemetryInstance) {
      if (openTelemetryInstance.isResolvable()) {
         this.otelLogger = openTelemetryInstance.get().getLogsBridge()
               .loggerBuilder(INSTRUMENTATION_SCOPE_NAME).build();
         logger.info("Audit logger initialized with OpenTelemetry — audit events will be emitted.");
      } else {
         this.otelLogger = null;
         logger.warn("OpenTelemetry is not available — audit logging will be disabled. "
               + "Enable OpenTelemetry (quarkus.otel.enabled=true) to activate audit event emission.");
      }
   }

   /**
    * Emit an audit log record for the given MCP call event.
    * @param event The audit event describing who called what and the outcome.
    */
   public void logMcpCall(AuditEvent event) {
      if (otelLogger == null) {
         return;
      }
      LogRecordBuilder builder = otelLogger.logRecordBuilder()
            .setTimestamp(Instant.now())
            .setBody(buildBody(event))
            .setAllAttributes(buildAttributes(event));

      if (AuditEvent.OUTCOME_FAILURE.equals(event.outcome())) {
         builder.setSeverity(Severity.WARN)
               .setSeverityText("WARN");
      } else {
         builder.setSeverity(Severity.INFO)
               .setSeverityText("INFO");
      }

      builder.emit();
   }

   private String buildBody(AuditEvent event) {
      StringBuilder sb = new StringBuilder("MCP audit: ")
            .append(event.method())
            .append(" on ").append(event.serviceName()).append(":").append(event.serviceVersion());
      if (event.targetName() != null) {
         sb.append(", target=").append(event.targetName());
      }
      sb.append(", outcome=").append(event.outcome());
      sb.append(", duration=").append(event.durationMs()).append("ms");
      if (event.userId() != null) {
         sb.append(", user=").append(event.userId());
      }
      if (event.codeMode() != null) {
         sb.append(", code=").append(event.codeMode().hash());
      }
      return sb.toString();
   }

   private Attributes buildAttributes(AuditEvent event) {
      AttributesBuilder ab = Attributes.builder()
            .put(LOG_TYPE, "audit")
            .put(EVENT_ACTION, event.method())
            .put(EVENT_OUTCOME, event.outcome())
            .put(EVENT_DURATION, event.durationMs())
            .put(SERVICE_NAME, event.serviceName())
            .put(SERVICE_VERSION, event.serviceVersion())
            .put(ORGANIZATION_ID, event.organizationId())
            .put(MCP_RESPONSE_SIZE, event.responseSize());

      if (event.requestId() != null) {
         ab.put(MCP_REQUEST_ID, event.requestId().toString());
      }
      if (event.sessionId() != null) {
         ab.put(MCP_SESSION_ID, event.sessionId());
      }
      if (event.targetName() != null) {
         ab.put(MCP_TARGET_NAME, event.targetName());
      }
      if (event.errorCode() != null) {
         ab.put(MCP_ERROR_CODE, event.errorCode().longValue());
      }
      if (event.sourceIp() != null) {
         ab.put(SOURCE_IP, event.sourceIp());
      }
      if (event.userId() != null) {
         ab.put(USER_ID, event.userId());
      }
      if (event.traceId() != null) {
         ab.put(TRACE_ID, event.traceId());
      }
      if (event.codeMode() != null) {
         ab.put(MCP_CODE_HASH, event.codeMode().hash());
         if (event.codeMode().code() != null) {
            ab.put(MCP_CODE, event.codeMode().code());
         }
      }
      return ab.build();
   }

   /**
    * Emit an audit log record for an authentication failure.
    * @param event The auth failure event describing the failed authentication attempt.
    */
   public void logAuthFailure(AuthenticationFailureAuditEvent event) {
      if (otelLogger == null) {
         return;
      }
      LogRecordBuilder builder = otelLogger.logRecordBuilder()
            .setTimestamp(Instant.now())
            .setSeverity(Severity.WARN)
            .setSeverityText("WARN")
            .setBody(buildAuthFailureBody(event))
            .setAllAttributes(buildAuthFailureAttributes(event));

      builder.emit();
   }

   private String buildAuthFailureBody(AuthenticationFailureAuditEvent event) {
      return "MCP audit: authentication failure on " + event.serviceName() + ":" + event.serviceVersion()
            + ", reason=" + event.reason()
            + ", http_status=" + event.httpStatus()
            + (event.sourceIp() != null ? ", source=" + event.sourceIp() : "");
   }

   private Attributes buildAuthFailureAttributes(AuthenticationFailureAuditEvent event) {
      AttributesBuilder ab = Attributes.builder()
            .put(LOG_TYPE, "audit")
            .put(EVENT_ACTION, "authentication")
            .put(EVENT_OUTCOME, AuditEvent.OUTCOME_FAILURE)
            .put(EVENT_REASON, event.reason())
            .put(HTTP_STATUS, (long) event.httpStatus())
            .put(SERVICE_ID, event.serviceId())
            .put(SERVICE_NAME, event.serviceName())
            .put(SERVICE_VERSION, event.serviceVersion())
            .put(ORGANIZATION_ID, event.organizationId());

      if (event.sourceIp() != null) {
         ab.put(SOURCE_IP, event.sourceIp());
      }
      if (event.traceId() != null) {
         ab.put(TRACE_ID, event.traceId());
      }
      return ab.build();
   }
}
