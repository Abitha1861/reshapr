/*
 * Copyright The Reshapr Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
export class Context {
  static currentContext: Record<string, unknown> = {};

  static put(key: string, value: unknown) {
    Context.currentContext[key] = value;
  }

  static isEmpty(): boolean {
    return Context.size() === 0;
  }

  static size(): number {
    return Object.keys(Context.currentContext).length;
  }

  static get(key: string): unknown {
    return Context.currentContext[key];
  }

  static getAll(): Record<string, unknown> {
    return Context.currentContext;
  }

  static clear() {
    Context.currentContext = {};
  }
}