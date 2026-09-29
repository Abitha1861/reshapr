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
import * as os from 'node:os';
import * as fs from 'node:fs';

import { Logger } from './logger.js';
import { CLI_NAME } from '../constants.js';

export class ConfigUtil {

  static configPath = `${os.homedir()}/.${CLI_NAME}/config`;
  
  static config: Config;

  static writeConfig(config: Config): void {
    if (!fs.existsSync(ConfigUtil.configPath)) {
      try {
        fs.mkdirSync(`${os.homedir()}/.${CLI_NAME}`, { recursive: true, mode: 0o700 });
      } catch (err) {
        Logger.error('Failed to create config directory: ' + err);
        process.exit(1);
      }
    }
    // Extract expiration time and org claim from the token if it exists.
    if (config.token) {
      try {
        const tokenParts = config.token.split('.');
        if (tokenParts.length === 3) {
          const payload = JSON.parse(Buffer.from(tokenParts[1], 'base64').toString('utf-8'));
          if (payload.exp) {
            config.exp= payload.exp.toString();
          }
          if (payload.org) {
            config.org = payload.org;
          }
        }
      } catch (err) {
        Logger.error('Failed to parse token: ' + err);
      }
    }

    fs.writeFileSync(ConfigUtil.configPath, JSON.stringify(config, null, 2), { mode: 0o600 });
    Logger.success(`Configuration saved to ${ConfigUtil.configPath}`);
    ConfigUtil.config = config;
  }

  static readConfig(): void {
    if (fs.existsSync(ConfigUtil.configPath)) {
      try {
        const configData = fs.readFileSync(ConfigUtil.configPath, 'utf-8');
        ConfigUtil.config = JSON.parse(configData) as Config;
      } catch (err) {
        Logger.error('Failed to read config file: ' + err);
      }
    }
  }

  static deleteConfig(): void {
    if (fs.existsSync(ConfigUtil.configPath)) {
      try {
        fs.unlinkSync(ConfigUtil.configPath);
        Logger.success('Configuration deleted successfully.');
      } catch (err) {
        Logger.error('Failed to delete config file: ' + err);
      }
    } else {
      Logger.warn('No configuration file found to delete.');
    }
  }
}

export interface Config {
  username: string;
  server: string;
  token: string;
  exp?: number;
  org?: string;
  insecure?: boolean;
}