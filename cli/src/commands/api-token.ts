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
import { Option, program } from "commander";
import inquirer from "inquirer";
import { Logger } from "../utils/logger.js";
import { ConfigUtil } from "../utils/config.js";
import { Context } from "../utils/context.js";
import { CLI_LABEL } from '../constants.js';

export const tokenCommand = program.command('api-token')
  .description(`Manage API tokens in ${CLI_LABEL}`);

/* List all api tokens */  
tokenCommand.command('list')
  .description('List all API tokens')
  .action(async () => {
    const response = await fetch(`${ConfigUtil.config.server}/api/v1/tokens/apiTokens`, {
      method: 'GET',
      headers: {
        'Authorization': `Bearer ${ConfigUtil.config.token}`
      }
    });

    if (!response.ok) {
      Logger.error(`Failed to fetch API tokens: ${response.statusText}`);
      process.exit(1);
    }
    const data = await response.json().catch(err => {
      Logger.error('Error parsing secrets response: ' + err.message);
    });

    if (data != null) {
      if (data.length === 0) {
        Logger.info('No API tokens found.');
      } else {
        Context.put('tokens', data);
        const longestName = longestTokenName(data) + 1; // +1 for padding

        Logger.log(`${'ID'.padEnd(13, ' ')}  ${'NAME'.padEnd(longestName, ' ')} VALID UNTIL`);
        data.forEach((token: { id: string; name: string; validUntil: string }) => {
          Logger.log(`${token.id.padEnd(13, ' ')}  ${token.name.padEnd(longestName, ' ')} ${new Date(token.validUntil).toUTCString()}`);
        });
      }
    }
  });

function longestTokenName(tokens: {name: string}[]) {
  return tokens.reduce((max, token) => {
    return Math.max(max, token.name.length);
  }, 0);
}

/* Create a new api token */
tokenCommand.command('create <name>')
  .description('Create a new API token')
  .addOption(new Option('-v, --validity-days <days>', 'Number of days the token is valid for').choices(['1', '7', '30', '90']))  
  .action(async (name: string, options) => {
    // Initialize the token request object.
    const tokenRequest = {
      name: name,
      validityDays: 30
    }
    // Populate validityDays if provided.
    if (options.validityDays) {
      tokenRequest.validityDays = parseInt(options.validityDays, 10);
    }
    const response = await fetch(`${ConfigUtil.config.server}/api/v1/tokens/apiTokens`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${ConfigUtil.config.token}`
      },
      body: JSON.stringify(tokenRequest)
    });

    if (!response.ok) {
      Logger.error(`Creating API token failed: ${response.statusText}`);
      process.exit(1);
    }

    const token = await response.json().catch(err => {
      Logger.error('Error parsing create token response: ' + err.message);
    });

    Context.put('token', token);

    if (token != null) {
      Logger.warn(`The API Token to register Gateway is: ${token.organizationId}-${token.token}`);
      Logger.warn('Make sure to store it securely, as it will not be shown again.');
    }
  }); 

/* Delete an api token */
tokenCommand.command('delete <tokenId>')
  .description('Delete an API token by ID')
  .option('-f, --force', 'Skip confirmation prompt')
  .action(async (tokenId: string, options) => {
    if (!options.force) {
      const confirm = await inquirer.prompt({
        type: 'confirm',
        name: 'confirm',
        message: `Deleting this API token will prevent Gateways that use it to connect to ${CLI_LABEL}. Are you sure you want to proceed?`,
        default: false
      });
      if (!confirm.confirm) {
        Logger.info('Deletion cancelled.');
        return;
      }
    }
    
    const response = await fetch(`${ConfigUtil.config.server}/api/v1/tokens/apiTokens/${tokenId}`, {
      method: 'DELETE',
      headers: {
        'Authorization': `Bearer ${ConfigUtil.config.token}`
      }
    });

    if (!response.ok) {
      Logger.error(`Failed to delete API token: ${response.statusText}`);
      return;
    }

    Logger.info(`API token with ID ${tokenId} deleted successfully.`);
  });