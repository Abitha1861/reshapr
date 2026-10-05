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
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
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
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HTTP-level integration test of MCP Code Mode. A JSONPlaceholder-shaped REST backend is stubbed on a
 * local port, exposed through three configuration plans ({@code TOOLS}, {@code CODE} and {@code HYBRID})
 * backed by the very same OpenAPI artifact, and driven through the real {@code /mcp/{expositionId}}
 * endpoint.
 * <p>
 * The point is to assert the whole chain the way an MCP client sees it: what {@code tools/list} advertises
 * per mode, that {@code search_tools} / {@code get_api_types} really disclose the API progressively, and
 * that an {@code execute_code} snippet chains several backend calls inside the gateway and returns only
 * the reduced answer — the entire reason Code Mode exists.
 * @author laurent
 */
@QuarkusTest
class McpCodeModeIntegrationTest {

   private static final String TOOLS_EXPOSITION = "code-mode-tools-exp";
   private static final String CODE_EXPOSITION = "code-mode-code-exp";
   private static final String HYBRID_EXPOSITION = "code-mode-hybrid-exp";

   /** Tool names produced by the OpenAPI converter for the stubbed JSONPlaceholder operations. */
   private static final String LIST_POSTS = "get_posts";
   private static final String GET_POST = "get_posts_id";
   private static final String LIST_COMMENTS = "get_posts_id_comments";
   private static final String GET_USER = "get_users_id";
   private static final String LIST_ALBUMS = "get_albums";

   /** The stubbed JSONPlaceholder backend, and the number of requests it served. */
   private static HttpServer backend;
   private static String backendEndpoint;
   private static final java.util.concurrent.atomic.AtomicInteger backendCalls =
         new java.util.concurrent.atomic.AtomicInteger();

   @Inject
   GatewayRegistry registry;

   // ---------------------------------------------------------------------------------------------
   // Stubbed backend: a tiny, deterministic subset of https://jsonplaceholder.typicode.com
   // ---------------------------------------------------------------------------------------------

   @BeforeAll
   static void startBackend() throws IOException {
      backend = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      backend.createContext("/", McpCodeModeIntegrationTest::handle);
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
      backendCalls.incrementAndGet();
      String path = exchange.getRequestURI().getPath();
      String query = exchange.getRequestURI().getQuery();
      String body;

      if ("/posts".equals(path)) {
         // Two authors, three posts; the userId filter mirrors the real JSONPlaceholder behaviour.
         String all = """
               [{"id":1,"userId":1,"title":"first post","body":"aaa"},\
               {"id":2,"userId":1,"title":"second post","body":"bbb"},\
               {"id":3,"userId":2,"title":"third post","body":"ccc"}]""";
         body = (query != null && query.contains("userId=2"))
               ? "[{\"id\":3,\"userId\":2,\"title\":\"third post\",\"body\":\"ccc\"}]" : all;
      } else if (path.matches("/posts/\\d+/comments")) {
         String postId = path.replaceAll("\\D+", "");
         body = """
               [{"id":10,"postId":%s,"name":"c1","email":"a@acme.io","body":"nice"},\
               {"id":11,"postId":%s,"name":"c2","email":"b@acme.io","body":"meh"}]"""
               .formatted(postId, postId);
      } else if (path.matches("/posts/\\d+")) {
         String postId = path.substring("/posts/".length());
         body = "{\"id\":%s,\"userId\":1,\"title\":\"post %s\",\"body\":\"lorem ipsum\"}"
               .formatted(postId, postId);
      } else if (path.matches("/users/\\d+")) {
         String userId = path.substring("/users/".length());
         body = """
               {"id":%s,"name":"User %s","username":"user%s","email":"user%s@acme.io"}"""
               .formatted(userId, userId, userId, userId);
      } else if ("/albums".equals(path)) {
         body = "[{\"id\":1,\"userId\":1,\"title\":\"an album\"}]";
      } else {
         exchange.sendResponseHeaders(404, -1);
         exchange.close();
         return;
      }

      byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().add("Content-Type", "application/json");
      exchange.sendResponseHeaders(200, bytes.length);
      exchange.getResponseBody().write(bytes);
      exchange.close();
   }

   // ---------------------------------------------------------------------------------------------
   // Registry seeding: one service, one artifact, three configuration plans.
   // ---------------------------------------------------------------------------------------------

   @BeforeEach
   void seedExpositions() {
      backendCalls.set(0);
      String specification = TestResources.readString("io/reshapr/proxy/mcp/code/jsonplaceholder-openapi.yaml");
      ArtifactEntry artifact = new ArtifactEntry("code-mode-artifact", "jsonplaceholder-openapi.yaml", "",
            ArtifactEntryType.OPEN_API_SPEC, true, specification);

      ServiceEntry service = new ServiceEntry("code-mode-svc", "acme", "JSONPlaceholder", "1.0.0", "REST",
            List.of(new OperationEntry("GET /posts", "GET", null, null, null),
                  new OperationEntry("GET /posts/{id}", "GET", null, null, null),
                  new OperationEntry("GET /posts/{id}/comments", "GET", null, null, null),
                  new OperationEntry("GET /users/{id}", "GET", null, null, null),
                  new OperationEntry("GET /albums", "GET", null, null, null)));

      registry.addExposition(new ExpositionEntry(TOOLS_EXPOSITION, "jsonplaceholder-tools", service,
            plan("cfg-tools", ToolExposureMode.TOOLS, List.of()), artifact, List.of()));
      // The CODE plan deliberately hides GET /albums, to prove the snippet cannot reach it either.
      registry.addExposition(new ExpositionEntry(CODE_EXPOSITION, "jsonplaceholder-code", service,
            plan("cfg-code", ToolExposureMode.CODE, List.of("GET /albums")), artifact, List.of()));
      registry.addExposition(new ExpositionEntry(HYBRID_EXPOSITION, "jsonplaceholder-hybrid", service,
            plan("cfg-hybrid", ToolExposureMode.HYBRID, List.of()), artifact, List.of()));
   }

   /** An unsecured plan (no apiKey, no OAuth2, no backend secret) so the MCP endpoint stays public. */
   private static ConfigurationEntry plan(String id, ToolExposureMode mode, List<String> excluded) {
      return new ConfigurationEntry(id, id, backendEndpoint, null, excluded, List.of(),
            null, null, null, false, null, null, mode);
   }

   // ---------------------------------------------------------------------------------------------
   // tools/list shaping
   // ---------------------------------------------------------------------------------------------

   @Test
   @DisplayName("TOOLS mode advertises one tool per operation and no meta-tool")
   void testToolsModeAdvertisesNativeToolsOnly() {
      given()
            .contentType(ContentType.JSON)
            .header(McpSchema.HEADER_PROTOCOL_VERSION, McpSchema.PROTOCOL_VERSION_STATELESS)
            .body(jsonRpc("tools/list", "{}"))
      .when()
            .post("/mcp/{expositionId}", TOOLS_EXPOSITION)
      .then()
            .statusCode(200)
            .body("error", nullValue())
            .body("result.tools", hasSize(5))
            .body("result.tools.name", hasItems(LIST_POSTS, GET_POST, LIST_COMMENTS, GET_USER, LIST_ALBUMS))
            .body("result.tools.name", not(hasItem(CodeModeTools.EXECUTE_CODE)));
   }

   @Test
   @DisplayName("CODE mode collapses the whole API to the three meta-tools")
   void testCodeModeAdvertisesOnlyTheMetaTools() {
      given()
            .contentType(ContentType.JSON)
            .header(McpSchema.HEADER_PROTOCOL_VERSION, McpSchema.PROTOCOL_VERSION_STATELESS)
            .body(jsonRpc("tools/list", "{}"))
      .when()
            .post("/mcp/{expositionId}", CODE_EXPOSITION)
      .then()
            .statusCode(200)
            .body("result.tools", hasSize(3))
            .body("result.tools.name", hasItems(CodeModeTools.SEARCH_TOOLS, CodeModeTools.GET_API_TYPES,
                  CodeModeTools.EXECUTE_CODE))
            .body("result.tools.name", not(hasItem(LIST_POSTS)))
            // The search_tools description tells the model how much there is to discover: 4 of 5 operations,
            // since the plan excludes GET /albums.
            .body("result.tools[0].description", containsString("4 operations"));
   }

   @Test
   @DisplayName("HYBRID mode advertises the native tools and the meta-tools together")
   void testHybridModeAdvertisesBoth() {
      given()
            .contentType(ContentType.JSON)
            .header(McpSchema.HEADER_PROTOCOL_VERSION, McpSchema.PROTOCOL_VERSION_STATELESS)
            .body(jsonRpc("tools/list", "{}"))
      .when()
            .post("/mcp/{expositionId}", HYBRID_EXPOSITION)
      .then()
            .statusCode(200)
            .body("result.tools", hasSize(8))
            .body("result.tools.name", hasItems(LIST_POSTS, LIST_ALBUMS,
                  CodeModeTools.SEARCH_TOOLS, CodeModeTools.GET_API_TYPES, CodeModeTools.EXECUTE_CODE));
   }

   // ---------------------------------------------------------------------------------------------
   // Progressive disclosure: search_tools then get_api_types
   // ---------------------------------------------------------------------------------------------

   @Test
   @DisplayName("search_tools discloses only the matching operations, without touching the backend")
   void testSearchToolsDisclosesTheMatchingOperations() {
      String content = callToolContent(CODE_EXPOSITION, CodeModeTools.SEARCH_TOOLS,
            "{\"query\":\"comments\"}");

      assertTrue(content.contains(LIST_COMMENTS), content);
      assertFalse(content.contains(GET_USER), content);
      // Discovery is served from the registry: no backend round-trip at all.
      assertEquals(0, backendCalls.get());
   }

   @Test
   @DisplayName("search_tools never discloses an operation the configuration plan excludes")
   void testSearchToolsHonorsThePlan() {
      String content = callToolContent(CODE_EXPOSITION, CodeModeTools.SEARCH_TOOLS, "{}");

      assertTrue(content.contains("\"exposed\":4"), content);
      assertFalse(content.contains(LIST_ALBUMS), content);
   }

   @Test
   @DisplayName("get_api_types returns a usable TypeScript surface for the requested tools")
   void testGetApiTypesReturnsTheTypeScriptSurface() {
      String types = callToolContent(CODE_EXPOSITION, CodeModeTools.GET_API_TYPES,
            "{\"tools\":[\"" + LIST_POSTS + "\",\"" + LIST_COMMENTS + "\"]}");

      assertTrue(types.contains("type ToolResult"), types);
      assertTrue(types.contains(LIST_POSTS + "(input:"), types);
      assertTrue(types.contains(LIST_COMMENTS + "(input:"), types);
      // The OpenAPI parameters are carried over, descriptions included.
      assertTrue(types.contains("userId"), types);
      assertTrue(types.contains("Only return the posts written by this user."), types);
      assertFalse(types.contains(GET_USER + "(input:"), types);
      assertEquals(0, backendCalls.get());
   }

   @Test
   @DisplayName("get_api_types rejects a tool the plan hides")
   void testGetApiTypesRejectsAHiddenTool() {
      given()
            .contentType(ContentType.JSON)
            .header(McpSchema.HEADER_PROTOCOL_VERSION, McpSchema.PROTOCOL_VERSION_STATELESS)
            .body(callTool(CodeModeTools.GET_API_TYPES, "{\"tools\":[\"" + LIST_ALBUMS + "\"]}"))
      .when()
            .post("/mcp/{expositionId}", CODE_EXPOSITION)
      .then()
            .statusCode(200)
            .body("result", nullValue())
            .body("error.code", equalTo(McpSchema.ErrorCodes.INVALID_PARAMS))
            .body("error.message", containsString(LIST_ALBUMS));
   }

   // ---------------------------------------------------------------------------------------------
   // execute_code: the actual Code Mode payoff
   // ---------------------------------------------------------------------------------------------

   @Test
   @DisplayName("execute_code chains several backend calls and returns only the reduced answer")
   void testExecuteCodeChainsCallsAndReducesThePayload() {
      // The classic N+1 orchestration: list the posts, then fetch the author and the comment count of
      // each one. In tools mode this would be 1 + 2*N round-trips through the model's context.
      String script = """
            const posts = api.%s({ userId: 2 });
            if (!posts.ok) rs.fail('cannot list posts', posts.error);
            const out = [];
            for (const post of posts.content) {
              const author = api.%s({ id: post.userId });
              const comments = api.%s({ id: post.id });
              out.push({ title: post.title, author: author.content.username, comments: comments.content.length });
            }
            return out;
            """.formatted(LIST_POSTS, GET_USER, LIST_COMMENTS);

      String content = callToolContent(CODE_EXPOSITION, CodeModeTools.EXECUTE_CODE, codeArgument(script));

      assertEquals("[{\"title\":\"third post\",\"author\":\"user2\",\"comments\":2}]", content);
      // 3 backend calls happened inside the gateway, 1 MCP round-trip reached the client.
      assertEquals(3, backendCalls.get());
      // The raw post bodies never leave the sandbox.
      assertFalse(content.contains("lorem ipsum"), content);
      assertFalse(content.contains("@acme.io"), content);
   }

   @Test
   @DisplayName("execute_code can aggregate without returning any intermediate payload")
   void testExecuteCodeAggregates() {
      String script = """
            const posts = api.%s({});
            let total = 0;
            for (const post of posts.content) {
              total += api.%s({ id: post.id }).content.length;
            }
            return { posts: posts.content.length, comments: total };
            """.formatted(LIST_POSTS, LIST_COMMENTS);

      String content = callToolContent(CODE_EXPOSITION, CodeModeTools.EXECUTE_CODE, codeArgument(script));

      assertEquals("{\"posts\":3,\"comments\":6}", content);
   }

   @Test
   @DisplayName("execute_code cannot reach an operation excluded by the configuration plan")
   void testExecuteCodeCannotReachAnExcludedOperation() {
      String script = """
            return { bound: typeof api.%s, direct: rs.callTool('%s', {}) };
            """.formatted(LIST_ALBUMS, LIST_ALBUMS);

      String content = callToolContent(CODE_EXPOSITION, CodeModeTools.EXECUTE_CODE, codeArgument(script));

      assertTrue(content.contains("\"bound\":\"undefined\""), content);
      assertTrue(content.contains("allow-list"), content);
      // The excluded backend route was never called.
      assertEquals(0, backendCalls.get());
   }

   @Test
   @DisplayName("a failing script is surfaced as a tool error so the model can correct it")
   void testFailingScriptIsSurfacedAsAToolError() {
      Response response = given()
            .contentType(ContentType.JSON)
            .header(McpSchema.HEADER_PROTOCOL_VERSION, McpSchema.PROTOCOL_VERSION_STATELESS)
            .body(callTool(CodeModeTools.EXECUTE_CODE, codeArgument("return this is not javascript;")))
      .when()
            .post("/mcp/{expositionId}", CODE_EXPOSITION)
      .then()
            .statusCode(200)
            // Not a JSON-RPC error: the model must read the message and retry with corrected code.
            .body("error", nullValue())
            .body("result.isError", equalTo(true))
            .extract().response();

      assertTrue(response.jsonPath().getString("result.content[0].text").contains("failed to compile"));
   }

   @Test
   @DisplayName("execute_code is rejected when the script submits no code")
   void testExecuteCodeRequiresTheCodeArgument() {
      given()
            .contentType(ContentType.JSON)
            .header(McpSchema.HEADER_PROTOCOL_VERSION, McpSchema.PROTOCOL_VERSION_STATELESS)
            .body(callTool(CodeModeTools.EXECUTE_CODE, "{}"))
      .when()
            .post("/mcp/{expositionId}", CODE_EXPOSITION)
      .then()
            .statusCode(200)
            .body("error.code", equalTo(McpSchema.ErrorCodes.INVALID_PARAMS));
   }

   // ---------------------------------------------------------------------------------------------
   // Dispatch rules across the modes
   // ---------------------------------------------------------------------------------------------

   @Test
   @DisplayName("a native tool call is refused in CODE mode, with a pointer to the meta-tools")
   void testNativeToolCallIsRefusedInCodeMode() {
      given()
            .contentType(ContentType.JSON)
            .header(McpSchema.HEADER_PROTOCOL_VERSION, McpSchema.PROTOCOL_VERSION_STATELESS)
            .body(callTool(LIST_POSTS, "{}"))
      .when()
            .post("/mcp/{expositionId}", CODE_EXPOSITION)
      .then()
            .statusCode(200)
            .body("result", nullValue())
            .body("error.code", equalTo(McpSchema.ErrorCodes.INVALID_PARAMS))
            .body("error.message", containsString(CodeModeTools.EXECUTE_CODE));

      assertEquals(0, backendCalls.get());
   }

   @Test
   @DisplayName("HYBRID mode accepts a native tool call and a meta-tool call alike")
   void testHybridModeAcceptsBothKindsOfCall() {
      String nativeContent = callToolContent(HYBRID_EXPOSITION, GET_POST, "{\"id\":7}");
      assertTrue(nativeContent.contains("\"title\":\"post 7\""), nativeContent);

      String script = "return api.%s({ id: 7 }).content.title;".formatted(GET_POST);
      String codeContent = callToolContent(HYBRID_EXPOSITION, CodeModeTools.EXECUTE_CODE, codeArgument(script));
      assertEquals("\"post 7\"", codeContent);
   }

   @Test
   @DisplayName("the meta-tools are unknown to a plan left in TOOLS mode")
   void testMetaToolsAreNotCallableInToolsMode() {
      given()
            .contentType(ContentType.JSON)
            .header(McpSchema.HEADER_PROTOCOL_VERSION, McpSchema.PROTOCOL_VERSION_STATELESS)
            .body(callTool(CodeModeTools.EXECUTE_CODE, codeArgument("return 1;")))
      .when()
            .post("/mcp/{expositionId}", TOOLS_EXPOSITION)
      .then()
            .statusCode(200)
            .body("result", nullValue())
            .body("error.code", equalTo(McpSchema.ErrorCodes.INVALID_PARAMS))
            .body("error.message", containsString("Unknown tool"));
   }

   // ---------------------------------------------------------------------------------------------
   // Helpers
   // ---------------------------------------------------------------------------------------------

   /** Call a tool through the MCP endpoint and return the text content of a successful result. */
   private static String callToolContent(String expositionId, String toolName, String argumentsJson) {
      Response response = given()
            .contentType(ContentType.JSON)
            .header(McpSchema.HEADER_PROTOCOL_VERSION, McpSchema.PROTOCOL_VERSION_STATELESS)
            .body(callTool(toolName, argumentsJson))
      .when()
            .post("/mcp/{expositionId}", expositionId)
      .then()
            .statusCode(200)
            .body("error", nullValue())
            .extract().response();
      return response.jsonPath().getString("result.content[0].text");
   }

   /** Build a {@code tools/call} JSON-RPC request. */
   private static String callTool(String toolName, String argumentsJson) {
      return jsonRpc("tools/call",
            "{\"name\":\"%s\",\"arguments\":%s}".formatted(toolName, argumentsJson));
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
            case '\t' -> escaped.append("\\t");
            default -> escaped.append(c);
         }
      }
      return "{\"code\":\"" + escaped + "\"}";
   }

   /** Build a JSON-RPC request envelope; the protocol version is carried by the request header. */
   private static String jsonRpc(String method, String params) {
      return """
            {"jsonrpc":"2.0","id":1,"method":"%s","params":%s}""".formatted(method, params);
   }
}
