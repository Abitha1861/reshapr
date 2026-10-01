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

/**
 * `monaco-types` (pulled in by `monaco-yaml`) re-exports from
 * `monaco-editor/esm/vs/editor/editor.api.js`, a deep path that monaco-editor >= 0.56 no
 * longer exposes through its `exports` map. Without this shim the `MonacoYaml` type loses
 * its `IDisposable` base. The runtime counterpart is the `resolve.alias` in `vite.config.ts`.
 * Remove both once monaco-yaml / monaco-types target the new entry points.
 */
declare module 'monaco-editor/esm/vs/editor/editor.api.js' {
	export * from 'monaco-editor';
}
