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
import inquirer from "inquirer";
import { program } from "commander";
import { Logger } from "../utils/logger.js";
import { ConfigUtil } from "../utils/config.js";
import { ageFrom } from "../utils/age.js";
import { Context } from "../utils/context.js";
import { CLI_LABEL } from '../constants.js';

export const serviceCommand = program.command('service')
  .description(`Manage services in ${CLI_LABEL}`);

/* List all secrets */
serviceCommand.command('list')
  .description('List all services')
  .option('-o, --output <format>', 'Output format (json, yaml)')
  .action(async () => {
    const response = await fetch(`${ConfigUtil.config.server}/api/v1/services`, {
      method: 'GET',
      headers: {
        'Authorization': `Bearer ${ConfigUtil.config.token}`
      }
    });

    if (!response.ok) {
      Logger.error('Fetching services failed: ' + response.statusText);
      process.exit(1);
    }

    const data = await response.json().catch(err => {
      Logger.error('Failed to parse response: ' + err.message);
      process.exit(1);
    });

    if (data.length === 0) {
      Logger.info('No services found.');
    } else {
      Context.put('services', data);
      
      const longestName = longestServiceName(data) + 1; // +1 for padding
      const longestVersion = longestServiceVersion(data) + 1; // +1 for padding
      const longestType = longestServiceType(data) + 1; // +1 for padding

      Logger.log(`${'ID'.padEnd(13, ' ')}  ${'NAME'.padEnd(longestName, ' ')} ${'VERSION'.padEnd(Math.max(longestVersion, 7), ' ')}  ${'TYPE'.padEnd(longestType, ' ')} AGE`);
      data.forEach((service: {id: string; name: string; version: string; type: string; createdOn: string}) => {
        Logger.log(`${service.id}  ${service.name.padEnd(longestName, ' ')} ${service.version.padEnd(Math.max(longestVersion, 7), ' ')}  ${service.type.padEnd(longestType, ' ')} ${ageFrom(service.createdOn)}`);
      });
    }
  });

function longestServiceName(services: {name: string}[]) {
  return services.reduce((max, service) => {
    return Math.max(max, service.name.length);
  }, 0);
}

function longestServiceVersion(services: {version: string}[]) {
  return services.reduce((max, service) => {
    return Math.max(max, service.version.length);
  }, 0);
}

function longestServiceType(services: {type: string}[]) {
  return services.reduce((max, service) => {
    return Math.max(max, service.type.length);
  }, 0);
}

/** Get service by ID */
serviceCommand.command('get <id>')
  .description('Get service by ID')
  .option('-o, --output <format>', 'Output format (json, yaml)')
  .action(async (id) => {
    const response = await fetch(`${ConfigUtil.config.server}/api/v1/services/${id}`, {
      method: 'GET',
      headers: {
        'Authorization': `Bearer ${ConfigUtil.config.token}`
      }
    });

    if (!response.ok) {
      Logger.error('Fetching service failed: ' + response.statusText);
      process.exit(1);
    }

    const data = await response.json().catch(err => {
      Logger.error('Error parsing service response: ' + err.message);
    });

    if (data != null) {
      Context.put('service', data);

      Logger.info('Service details');
      Logger.log(`ID          : ${data.id}`);
      Logger.log(`Name        : ${data.name}`);
      Logger.log(`Version     : ${data.version}`);
      Logger.log(`Organization: ${data.organizationId}`);
      Logger.log(`Type        : ${data.type}`);
      Logger.log(`Created     : ${data.createdOn}`);
      Logger.bold('Operations :');
      if (data.operations && data.operations.length > 0) {
        data.operations.forEach((op: {name: string; inputName?: string; outputName?: string}) => {
          Logger.log(`  - Name: ${op.name}`);
          if (op.inputName) {
            Logger.log(`    Input: ${op.inputName}`);
          }
          if (op.outputName) {
            Logger.log(`    Output ${op.outputName}`);
          }
        });
      } else {
        Logger.log('  No operations defined for this service.');
      }
    }
  });

/** Delete service by ID */
serviceCommand.command('delete <id>')
  .description('Delete service by ID')
  .option('-f, --force', 'Skip confirmation prompt')
  .action(async (id, options) => {
    if (!options.force) {
      const confirm = await inquirer.prompt({
        type: 'confirm',
        name: 'confirm',
        message: 'Deleting this service will also remove associated artifacts, config plans & expositions. Are you sure you want to proceed?',
        default: false
      });
      if (!confirm.confirm) {
        Logger.info('Deletion cancelled.');
        return;
      }
    }

    const response = await fetch(`${ConfigUtil.config.server}/api/v1/services/${id}`, {
      method: 'DELETE',
      headers: {
        'Authorization': `Bearer ${ConfigUtil.config.token}`
      }
    });

    if (!response.ok) {
      Logger.error('Deleting service failed: ' + response.statusText);
      process.exit(1);
    }

    Logger.success(`Service ${id} deleted successfully.`);
  });