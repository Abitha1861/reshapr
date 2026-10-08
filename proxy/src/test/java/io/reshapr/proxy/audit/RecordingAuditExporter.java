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

import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.logs.data.LogRecordData;
import io.opentelemetry.sdk.logs.export.LogRecordExporter;
import jakarta.annotation.Nullable;
import jakarta.inject.Singleton;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Test-scoped sink capturing the audit {@link LogRecordData} the {@link AuditLogger} emits, so a test can
 * assert what truly reaches an OpenTelemetry collector rather than re-asserting the record that was handed
 * to the logger. Registered through the {@code cdi} log record exporter of the Quarkus OpenTelemetry
 * extension.
 * @author laurent
 */
@Singleton
public class RecordingAuditExporter implements LogRecordExporter {

   /** Default time budget for a batched record to reach this exporter. */
   private static final long AWAIT_TIMEOUT_MS = 15_000L;

   private final List<LogRecordData> records = new CopyOnWriteArrayList<>();

   @Override
   public CompletableResultCode export(Collection<LogRecordData> logRecords) {
      records.addAll(logRecords);
      return CompletableResultCode.ofSuccess();
   }

   @Override
   public CompletableResultCode flush() {
      return CompletableResultCode.ofSuccess();
   }

   @Override
   public CompletableResultCode shutdown() {
      return CompletableResultCode.ofSuccess();
   }

   /** Forget every captured record. */
   public void clear() {
      records.clear();
   }

   /**
    * Wait for the audit record of a given target to be exported. Records are batched, so a test cannot read
    * them synchronously after the HTTP call returns.
    * @param targetName The expected {@code mcp.target.name} attribute.
    * @return The matching audit record.
    * @throws AssertionError if no such record is exported within the time budget.
    */
   public LogRecordData awaitAuditRecord(String targetName) {
      long deadline = System.currentTimeMillis() + AWAIT_TIMEOUT_MS;
      while (System.currentTimeMillis() < deadline) {
         LogRecordData found = records.stream()
               .filter(record -> "audit".equals(attribute(record, "log.type")))
               .filter(record -> targetName.equals(attribute(record, "mcp.target.name")))
               .findFirst().orElse(null);
         if (found != null) {
            return found;
         }
         try {
            Thread.sleep(100L);
         } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            break;
         }
      }
      throw new AssertionError("No audit record exported for target '" + targetName + "'");
   }

   /**
    * Whether an audit record was exported for the given target. Does not wait: callers must first fence on a
    * record they do expect, so an absence is read from a pipeline known to have flushed.
    * @param targetName The expected {@code mcp.target.name} attribute.
    * @return {@code true} if such an audit record was captured.
    */
   public boolean hasAuditRecord(String targetName) {
      return records.stream()
            .filter(record -> "audit".equals(attribute(record, "log.type")))
            .anyMatch(record -> targetName.equals(attribute(record, "mcp.target.name")));
   }

   /** Read a string-valued attribute of a record, or {@code null} when absent. */
   @Nullable
   public static String attribute(LogRecordData record, String key) {
      Object value = rawAttribute(record, key);
      return value != null ? value.toString() : null;
   }

   /** Whether the record carries the given attribute at all. */
   public static boolean hasAttribute(LogRecordData record, String key) {
      return rawAttribute(record, key) != null;
   }

   @Nullable
   private static Object rawAttribute(LogRecordData record, String key) {
      return record.getAttributes().asMap().entrySet().stream()
            .filter(entry -> key.equals(entry.getKey().getKey()))
            .map(Map.Entry::getValue).findFirst().orElse(null);
   }
}
