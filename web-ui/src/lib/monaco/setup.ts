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

import EditorWorker from 'monaco-editor/editor/editor.worker.js?worker';
import YamlWorker from './yaml.worker.js?worker';
import { buildMonacoYamlSchemas } from './schemas.js';

import type * as Monaco from 'monaco-editor';
import type { MonacoYaml } from 'monaco-yaml';

type MonacoWebWorkerOptions = {
	worker?: Worker | Promise<Worker>;
	moduleId?: string;
	label?: string;
	createData?: unknown;
	host?: Record<string, (...args: unknown[]) => unknown>;
	keepIdleModels?: boolean;
};

let monacoPromise: Promise<typeof Monaco> | null = null;
let monacoYamlHandle: MonacoYaml | null = null;

/**
 * monaco-worker-manager calls `monaco.editor.createWebWorker({ moduleId, label, createData })`.
 * Monaco 0.55 expects `{ worker: Promise<Worker> }` instead — without this bridge the YAML worker
 * silently falls back to the editor worker and completion returns nothing.
 */
function patchMonacoWebWorker(monaco: typeof Monaco): void {
	const createWebWorker = monaco.editor.createWebWorker.bind(monaco.editor);

	monaco.editor.createWebWorker = ((opts: MonacoWebWorkerOptions) => {
		if (opts.moduleId && opts.label && !opts.worker) {
			const env = globalThis.MonacoEnvironment;
			if (!env?.getWorker) {
				throw new Error('MonacoEnvironment.getWorker is required for monaco-yaml');
			}

			const worker = Promise.resolve(env.getWorker('workerMain.js', opts.label)).then((w) => {
				w.postMessage('ignore');
				w.postMessage(opts.createData);
				return w;
			});

			return createWebWorker({
				worker,
				host: opts.host,
				keepIdleModels: opts.keepIdleModels
			});
		}

		return createWebWorker(opts as Parameters<typeof createWebWorker>[0]);
	}) as typeof monaco.editor.createWebWorker;
}

function configureWorkers(): void {
	if (globalThis.MonacoEnvironment) return;

	globalThis.MonacoEnvironment = {
		getWorker(_moduleId: string, label: string) {
			switch (label) {
				case 'editorWorkerService':
					return new EditorWorker();
				case 'yaml':
					return new YamlWorker();
				default:
					throw new Error(`Unknown Monaco worker label: ${label}`);
			}
		}
	};
}

/** Client-only Monaco + YAML language registration with JSON Schema validation. */
export async function ensureMonacoYaml(): Promise<typeof Monaco> {
	if (monacoPromise) return monacoPromise;

	monacoPromise = (async () => {
		configureWorkers();
		const monaco = await import('monaco-editor');
		patchMonacoWebWorker(monaco);
		const { configureMonacoYaml } = await import('monaco-yaml');

		monacoYamlHandle?.dispose();
		monacoYamlHandle = configureMonacoYaml(monaco, {
			enableSchemaRequest: false,
			validate: true,
			completion: true,
			hover: true,
			schemas: buildMonacoYamlSchemas()
		});

		return monaco;
	})();

	return monacoPromise;
}

export function getMonacoYamlHandle(): MonacoYaml | null {
	return monacoYamlHandle;
}
