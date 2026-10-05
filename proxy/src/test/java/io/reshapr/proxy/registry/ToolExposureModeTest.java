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

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Test case for {@link ToolExposureMode}, the data-plane mirror of the control-plane enum. Parsing must be
 * lenient: the gateway and the control plane are deployed independently, so an unknown or absent value has
 * to degrade to the classic tools mode rather than break the exposition.
 * @author laurent
 */
class ToolExposureModeTest {

   @Test
   void testFromValueParsesEveryMode() {
      assertEquals(ToolExposureMode.TOOLS, ToolExposureMode.fromValue("TOOLS"));
      assertEquals(ToolExposureMode.CODE, ToolExposureMode.fromValue("CODE"));
      assertEquals(ToolExposureMode.HYBRID, ToolExposureMode.fromValue("HYBRID"));
   }

   @Test
   void testFromValueIsCaseInsensitiveAndTrims() {
      assertEquals(ToolExposureMode.CODE, ToolExposureMode.fromValue("code"));
      assertEquals(ToolExposureMode.HYBRID, ToolExposureMode.fromValue("  Hybrid  "));
   }

   @Test
   void testFromValueFallsBackToTheDefault() {
      assertEquals(ToolExposureMode.DEFAULT, ToolExposureMode.fromValue(null));
      assertEquals(ToolExposureMode.DEFAULT, ToolExposureMode.fromValue(""));
      assertEquals(ToolExposureMode.DEFAULT, ToolExposureMode.fromValue("   "));
      // A mode introduced by a newer control plane must not break an older gateway.
      assertEquals(ToolExposureMode.DEFAULT, ToolExposureMode.fromValue("QUANTUM"));
   }

   @Test
   void testDefaultIsTheClassicToolsMode() {
      assertEquals(ToolExposureMode.TOOLS, ToolExposureMode.DEFAULT);
   }

   @Test
   void testExposesCodeMode() {
      assertFalse(ToolExposureMode.TOOLS.exposesCodeMode());
      assertTrue(ToolExposureMode.CODE.exposesCodeMode());
      assertTrue(ToolExposureMode.HYBRID.exposesCodeMode());
   }

   @Test
   void testExposesNativeTools() {
      assertTrue(ToolExposureMode.TOOLS.exposesNativeTools());
      assertFalse(ToolExposureMode.CODE.exposesNativeTools());
      assertTrue(ToolExposureMode.HYBRID.exposesNativeTools());
   }

   @Test
   void testEveryModeExposesSomething() {
      // There is no "expose nothing" mode: tools/list is never empty because of the exposure mode alone.
      for (ToolExposureMode mode : ToolExposureMode.values()) {
         assertTrue(mode.exposesNativeTools() || mode.exposesCodeMode(),
               "Mode " + mode + " exposes neither native tools nor the meta-tools");
      }
   }

   @Test
   void testEffectiveToolExposureModeOnConfigurationEntry() {
      ConfigurationEntry explicit = new ConfigurationEntry("c1", "cfg", "http://b", null,
            List.of(), List.of(), null, null, null, false, null, null, ToolExposureMode.CODE);
      assertEquals(ToolExposureMode.CODE, explicit.effectiveToolExposureMode());

      // Plans created before the feature existed carry no mode: they keep behaving as before.
      ConfigurationEntry legacy = new ConfigurationEntry("c2", "cfg", "http://b", null,
            List.of(), List.of(), null, null, null);
      assertNullSafeDefault(legacy);
   }

   private static void assertNullSafeDefault(ConfigurationEntry entry) {
      assertEquals(null, entry.toolExposureMode());
      assertEquals(ToolExposureMode.TOOLS, entry.effectiveToolExposureMode());
   }
}
