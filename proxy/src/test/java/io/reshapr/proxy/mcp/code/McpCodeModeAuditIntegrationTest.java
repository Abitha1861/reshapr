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

import io.reshapr.proxy.TestResources;
import io.reshapr.proxy.audit.RecordingAuditExporter;
import io.reshapr.proxy.mcp.McpSchema;
import io.reshapr.proxy.registry.ArtifactEntry;
import io.reshapr.proxy.registry.ArtifactEntryType;
import io.reshapr.proxy.registry.ConfigurationEntry;
import io.reshapr.proxy.registry.ExpositionEntry;
import io.reshapr.proxy.registry.GatewayRegistry;
import io.reshapr.proxy.registry.OperationEntry;
import io.reshapr.proxy.registry.ServiceEntry;
import io.reshapr.proxy.registry.ToolExposureMode;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.opentelemetry.sdk.logs.data.LogRecordData;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Asserts what a Code Mode execution leaves behind in the audit trail, captured from the OpenTelemetry log
 * records the gateway actually exports.
 * <p>
 * The snippet an LLM submits is the one input an operator cannot reconstruct afterwards: the derived tool
 * calls show up as child spans of the same trace, but the code that produced them exists nowhere else. This
 * test pins the record down end to end — request parameters, controller extraction, audit attributes.
 * @author laurent
 */
@QuarkusTest
class McpCodeModeAuditIntegrationTest {

   private static final String AUDITED_CODE_EXPOSITION = "audit-code-exp";
   private static final String AUDITED_TOOLS_EXPOSITION = "audit-tools-exp";
   private static final String SILENT_CODE_EXPOSITION = "audit-silent-code-exp";

   private static final String LIST_POSTS = "get_posts";

   private static HttpServer backend;
   private static String backendEndpoint;

   @Inject
   GatewayRegistry registry;

   @Inject
   RecordingAuditExporter auditExporter;

   @BeforeAll
   static void startBackend() throws IOException {
      backend = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      backend.createContext("/", McpCodeModeAuditIntegrationTest::handle);
      backend.start();
      backendEndpoint = "http://127.0.0.1:" + backend.getAddress().getPort();
   }

   @AfterAll
   static void stopBackend() {
      if (backend != null) {
         backend.stop(0);
      }
   }

   private static void handle(HttpExchange exchange) throws IOException {
      byte[] bytes = "[{\"id\":1,\"userId\":1,\"title\":\"a post\",\"body\":\"aaa\"}]"
            .getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().add("Content-Type", "application/json");
      exchange.sendResponseHeaders(200, bytes.length);
      exchange.getResponseBody().write(bytes);
      exchange.close();
   }

   @BeforeEach
   void seedExpositions() {
      auditExporter.clear();
      String specification = TestResources.readString("io/reshapr/proxy/mcp/code/jsonplaceholder-openapi.yaml");
      ArtifactEntry artifact = new ArtifactEntry("audit-artifact", "jsonplaceholder-openapi.yaml", "",
            ArtifactEntryType.OPEN_API_SPEC, true, specification);
      ServiceEntry service = new ServiceEntry("audit-svc", "acme", "JSONPlaceholder", "1.0.0", "REST",
            List.of(new OperationEntry("GET /posts", "GET", null, null, null)));

      registry.addExposition(new ExpositionEntry(AUDITED_CODE_EXPOSITION, "audited-code", service,
            plan("cfg-audit-code", ToolExposureMode.CODE, true), artifact, List.of()));
      registry.addExposition(new ExpositionEntry(AUDITED_TOOLS_EXPOSITION, "audited-tools", service,
            plan("cfg-audit-tools", ToolExposureMode.TOOLS, true), artifact, List.of()));
      registry.addExposition(new ExpositionEntry(SILENT_CODE_EXPOSITION, "silent-code", service,
            plan("cfg-silent-code", ToolExposureMode.CODE, false), artifact, List.of()));
   }

   private static ConfigurationEntry plan(String id, ToolExposureMode mode, boolean audit) {
      return new ConfigurationEntry(id, id, backendEndpoint, null, List.of(), List.of(),
            null, null, null, audit, null, null, mode);
   }

   @Test
   @DisplayName("an execute_code audit record carries the submitted snippet and its digest")
   void testExecuteCodeAuditRecordCarriesTheSnippet() {
      String snippet = "const posts = api.get_posts({});\nreturn { count: posts.data.length };";

      callTool(AUDITED_CODE_EXPOSITION, CodeModeTools.EXECUTE_CODE, codeArgument(snippet));

      LogRecordData record = auditExporter.awaitAuditRecord(CodeModeTools.EXECUTE_CODE);
      assertEquals(snippet, RecordingAuditExporter.attribute(record, "mcp.code"));
      assertEquals(CodeModeDigest.of(snippet), RecordingAuditExporter.attribute(record, "mcp.code.hash"));
   }

   @Test
   @DisplayName("the audit record carries the trace id that leads to the derived tool calls")
   void testExecuteCodeAuditRecordIsJoinableWithItsTrace() {
      // The derived calls are deliberately not duplicated in the audit stream: they are child spans of this
      // very trace. The join only works if the trace id is actually recorded, hence this assertion.
      callTool(AUDITED_CODE_EXPOSITION, CodeModeTools.EXECUTE_CODE,
            codeArgument("return api.get_posts({}).ok;"));

      LogRecordData record = auditExporter.awaitAuditRecord(CodeModeTools.EXECUTE_CODE);
      String traceId = RecordingAuditExporter.attribute(record, "trace.id");
      assertNotNull(traceId, "the audit record must carry a trace id");
      assertFalse(traceId.isBlank());
   }

   @Test
   @DisplayName("a discovery meta-tool is audited without any code attribute")
   void testDiscoveryCallsCarryNoCodeAttribute() {
      // search_tools carries no snippet, so recording an empty or inherited one would be misleading.
      callTool(AUDITED_CODE_EXPOSITION, CodeModeTools.SEARCH_TOOLS, "{}");

      LogRecordData record = auditExporter.awaitAuditRecord(CodeModeTools.SEARCH_TOOLS);
      assertFalse(RecordingAuditExporter.hasAttribute(record, "mcp.code"));
      assertFalse(RecordingAuditExporter.hasAttribute(record, "mcp.code.hash"));
   }

   @Test
   @DisplayName("a native tool call is audited without any code attribute")
   void testNativeToolCallsCarryNoCodeAttribute() {
      callTool(AUDITED_TOOLS_EXPOSITION, LIST_POSTS, "{}");

      LogRecordData record = auditExporter.awaitAuditRecord(LIST_POSTS);
      assertFalse(RecordingAuditExporter.hasAttribute(record, "mcp.code"));
      assertFalse(RecordingAuditExporter.hasAttribute(record, "mcp.code.hash"));
   }

   @Test
   @DisplayName("a plan with auditing off emits no audit record at all")
   void testCodeModeHonorsTheAuditFlagOfThePlan() {
      callTool(SILENT_CODE_EXPOSITION, CodeModeTools.EXECUTE_CODE,
            codeArgument("return api.get_posts({}).ok;"));

      // Fence on a record we do expect, emitted after the silent one: once it has been exported, the
      // pipeline has flushed and the absence of an execute_code record is a fact rather than a race.
      callTool(AUDITED_TOOLS_EXPOSITION, LIST_POSTS, "{}");
      auditExporter.awaitAuditRecord(LIST_POSTS);

      assertFalse(auditExporter.hasAuditRecord(CodeModeTools.EXECUTE_CODE),
            "auditing is disabled on this plan, no record should be exported");
   }

   /** Issue a {@code tools/call} and require a non-error JSON-RPC response. */
   private static void callTool(String expositionId, String toolName, String argumentsJson) {
      given()
            .contentType(ContentType.JSON)
            .header(McpSchema.HEADER_PROTOCOL_VERSION, McpSchema.PROTOCOL_VERSION_STATELESS)
            .body("""
                  {"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"%s","arguments":%s}}"""
                  .formatted(toolName, argumentsJson))
            .when()
            .post("/mcp/{expositionId}", expositionId)
            .then()
            .statusCode(200)
            .body("error", nullValue());
   }

   /** Build the {@code execute_code} arguments object carrying a snippet, properly JSON-escaped. */
   private static String codeArgument(String script) {
      StringBuilder escaped = new StringBuilder();
      for (char c : script.toCharArray()) {
         switch (c) {
            case '"' -> escaped.append("\\\"");
            case '\\' -> escaped.append("\\\\");
            case '\n' -> escaped.append("\\n");
            case '\r' -> escaped.append("\\r");
            default -> escaped.append(c);
         }
      }
      return "{\"code\":\"" + escaped + "\"}";
   }
}
