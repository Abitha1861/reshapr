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

import jakarta.annotation.Nullable;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * The digest identifying a model-authored Code Mode snippet across the places that record it.
 * Two sinks observe the same execution and must agree on its identity: the application log of
 * {@link CodeModeExecutor}, which is what remains when no OpenTelemetry collector is around, and the
 * structured audit event emitted by the MCP controller. Sharing one digest is what lets an operator pivot
 * between them, since the console log format carries no trace identifier.
 * @author laurent
 */
public final class CodeModeDigest {

   /** Number of hexadecimal characters kept from the digest. */
   private static final int LENGTH = 12;

   private CodeModeDigest() {
   }

   /**
    * Compute the short, stable digest of a submitted snippet.
    * @param code The submitted code, possibly {@code null}.
    * @return The truncated SHA-256 of the code, or {@code null} if there is no code to digest.
    */
   @Nullable
   public static String of(@Nullable String code) {
      if (code == null) {
         return null;
      }
      try {
         byte[] digest = MessageDigest.getInstance("SHA-256").digest(code.getBytes(StandardCharsets.UTF_8));
         return HexFormat.of().formatHex(digest).substring(0, LENGTH);
      } catch (NoSuchAlgorithmException e) {
         // SHA-256 is mandated by the platform; a missing provider is not something a caller can act on.
         throw new IllegalStateException("SHA-256 is not available", e);
      }
   }
}
