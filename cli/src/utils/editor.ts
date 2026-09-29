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
import * as os from 'os';
import * as path from 'path';
import { spawn } from 'child_process';
import { promises as fs } from 'fs';

import { Logger } from "../utils/logger.js";
import { CLI_NAME } from '../constants.js';

/** Open a text editor to change initialContent. Execute onChanged() if updated. */
export async function openUpdateEditor<T>(initialContent: T, onChanged: (modifiedContent: T) => Promise<void>): Promise<void> {

  // Create a temporary file with the current configuration
  const tempDir = os.tmpdir();
  const tempFile = path.join(tempDir, `${CLI_NAME}-update.json`);

  const initialData = JSON.stringify(initialContent, null, 2);
  await fs.writeFile(tempFile, initialData, { encoding: 'utf-8', mode: 0o600 });

  // Get the initial modification time
  const statsBefore = await fs.stat(tempFile);
  const mtimeBefore = statsBefore.mtime.getTime();

  // Open vi editor with the temporary file
  const editorProcess = spawn('vi', [tempFile], {
    stdio: 'inherit', // This allows vi to take control of the terminal
    shell: false
  });

  // Wait for the editor to close
  await new Promise<void>((resolve, reject) => {
    editorProcess.on('close', (code) => {
      if (code === 0) {
        resolve();
      } else {
        reject(new Error(`Editor exited with code ${code}`));
      }
    });

    editorProcess.on('error', (error) => {
      reject(error);
    });
  });

  // Check if the file was modified.
  const statsAfter = await fs.stat(tempFile);
  const mtimeAfter = statsAfter.mtime.getTime();

  if (mtimeAfter !== mtimeBefore) {
    let modifiedContent;
    try {
      modifiedContent = JSON.parse(await fs.readFile(tempFile, 'utf8'));
    } catch (error) {
      Logger.error('Error reading modified content: ' + (error as Error).message);
    }
    // Notify the caller of the changes.
    await onChanged(modifiedContent);
  } else {
    Logger.info('No changes detected. Object not modified.');
  }

  // Clean up the temporary file
  await fs.unlink(tempFile);
}
