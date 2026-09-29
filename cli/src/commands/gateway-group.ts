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
import { program } from "commander";
import { Logger } from "../utils/logger.js";
import { ConfigUtil } from "../utils/config.js";
import { Context } from "../utils/context.js";
import { CLI_LABEL } from '../constants.js';

export const gatewayGroupCommand = program.command('gateway-group')
  .description(`Manage gateway groups in ${CLI_LABEL}`);

/* List all gateway groups */
gatewayGroupCommand.command('list')
  .description('List all gateway groups')
  .option('-o, --output <format>', 'Output format (json, yaml)')
  .action(async () => {
    const response = await fetch(`${ConfigUtil.config.server}/api/v1/gatewayGroups`, {
      method: 'GET',
      headers: {
        'Authorization': `Bearer ${ConfigUtil.config.token}`
      }
    });

    if (!response.ok) {
      Logger.error('Fetching gateway groups failed: ' + response.statusText);
      process.exit(1);
    }

    const data = await response.json();
    if (data.length === 0) {
      Logger.info('No gateway groups found.');
    } else {
      Context.put('gatewayGroups', data);
      const longestName = longestGroupName(data) + 1; // +1 for padding
      const longestGroupOrganization = longestGroupOrganizationName(data) + 1; // +1 for padding

      Logger.log(`${'ID'.padEnd(13, ' ')}  ${'ORG'.padEnd(longestGroupOrganization, ' ')} ${'NAME'.padEnd(longestName, ' ')} ${'LABELS'.padEnd(60, ' ')}`);
      data.forEach((group: {id: string; organizationId: string; name: string; labels: Record<string, string>}) => {
        Logger.log(`${group.id.padEnd(13, ' ')}  ${group.organizationId.padEnd(longestGroupOrganization, ' ')} ${group.name.padEnd(longestName, ' ')} ${JSON.stringify(group.labels).padEnd(60, ' ')}`);
      });
    }
  });

function longestGroupName(groups: {name: string}[]) {
  return groups.reduce((max, group) => {
    return Math.max(max, group.name.length);
  }, 0);
}
function longestGroupOrganizationName(groups: {organizationId?: string}[]) {
  return groups.reduce((max, group) => {
    return Math.max(max, group.organizationId ? group.organizationId.length : 0);
  }, 0);
}

/** Create a new gateway group */
gatewayGroupCommand.command('create <name>')
  .description('Create a new gateway group')
  .option('-l, --labels <labels>', 'JSON map of key-values labels for the gateway group')
  .option('-o, --output <format>', 'Output format (json, yaml)')
  .action(async (name, options) => {
    const labels: Record<string, string> = options.labels ? JSON.parse(options.labels) : {};

    const response = await fetch(`${ConfigUtil.config.server}/api/v1/gatewayGroups`, {
      method: 'POST',
      headers: {
        'Authorization': `Bearer ${ConfigUtil.config.token}`,
        'Content-Type': 'application/json'
      },
      body: JSON.stringify({ 
        name: name,
        labels: labels
      })
    });

    if (!response.ok) {
      if (response.status === 429) {
        Logger.error('Gateway group creation quota exceeded. Check your quotas.');
      } else {
        Logger.error('Creating gateway group failed: ' + response.statusText);
      }
      process.exit(1);
    }

    const data = await response.json();
    Logger.success(`Gateway group '${data.name}' created successfully with ID: ${data.id}`);
    Context.put('gatewayGroup', data);
  });

/** Delete a gateway group by ID */
gatewayGroupCommand.command('delete <id>')
  .description('Delete a gateway group by ID')
  .action(async (id) => {
    const response = await fetch(`${ConfigUtil.config.server}/api/v1/gatewayGroups/${id}`, {
      method: 'DELETE',
      headers: {
        'Authorization': `Bearer ${ConfigUtil.config.token}`
      }
    });
    if (!response.ok) {
      Logger.error('Deleting gateway group failed: ' + response.statusText);
      process.exit(1);
    }
    Logger.success(`Gateway group with ID '${id}' deleted successfully.`);
  });