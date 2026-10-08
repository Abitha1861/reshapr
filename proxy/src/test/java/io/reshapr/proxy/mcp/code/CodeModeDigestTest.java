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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins down the properties the Code Mode digest is relied upon for: the application log and the audit event
 * must designate the same snippet with the same identifier, across gateway restarts and versions.
 * @author laurent
 */
class CodeModeDigestTest {

   @Test
   @DisplayName("the digest of a snippet is stable across calls and across JVM runs")
   void testDigestIsStable() {
      String code = "const posts = api.get_posts({});\nreturn posts.data.length;";

      // Hard-coded rather than compared to a second call: that is what makes this a regression guard against
      // a future change of algorithm or truncation length, which would silently break correlation with
      // records emitted by an older gateway.
      assertEquals("99b740273dce", CodeModeDigest.of(code));
      assertEquals(CodeModeDigest.of(code), CodeModeDigest.of(code));
   }

   @Test
   @DisplayName("snippets differing by a single character get different digests")
   void testDigestDiscriminatesSnippets() {
      assertNotEquals(CodeModeDigest.of("return api.get_posts({}).ok;"),
            CodeModeDigest.of("return api.get_posts({}).OK;"));
   }

   @Test
   @DisplayName("a missing snippet has no digest")
   void testNoCodeNoDigest() {
      // Discovery meta-tools carry no code: the audit record must omit the attribute rather than record the
      // digest of an empty string, which would look like a real, recurring snippet.
      assertNull(CodeModeDigest.of(null));
   }

   @Test
   @DisplayName("the digest is short enough to be read in a log line")
   void testDigestIsShortAndHexadecimal() {
      String digest = CodeModeDigest.of("return 1;");
      assertEquals(12, digest.length());
      assertTrue(digest.matches("[0-9a-f]{12}"), () -> "not lowercase hexadecimal: " + digest);
   }
}
