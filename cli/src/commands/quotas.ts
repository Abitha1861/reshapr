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
import { Command } from "commander";
import { Logger } from "../utils/logger.js";
import { ConfigUtil } from "../utils/config.js";
import { Context } from "../utils/context.js";
import { CLI_LABEL } from '../constants.js';

export const quotasCommand = new Command('quotas')
  .description(`List and check your ${CLI_LABEL} quotas`)
  .option('-o, --output <format>', 'Output format (json, yaml)')
  .action(async () => {
    const response = await fetch(`${ConfigUtil.config.server}/api/v1/quotas`, {
      method: 'GET',
      headers: {
        'Authorization': `Bearer ${ConfigUtil.config.token}`
      }
    });

    if (!response.ok) {
      Logger.error('Fetching quotas failed: ' + response.statusText);
      process.exit(1);
    }

    const data = await response.json().catch(err => {
      Logger.error('Error parsing quotas response: ' + err.message);
    });

    if (data.length === 0) {
      Logger.info('No quotas found.');
    } else {
      Context.put('quotas', data);
      const longestMetric = longestQuotaMetric(data) + 1; // +1 for padding
      const longestOrganization = longestQuotaOrganization(data) + 1;

      Logger.log(`${'ORG'.padEnd(longestOrganization, ' ')} ${'METRIC'.padEnd(longestMetric, ' ')} ${'ENABLED'.padEnd(8, ' ')} ${'LIMIT'.padEnd(6, ' ')} ${'REMAINING'.padEnd(10, ' ')}`);
      data.forEach((quota: {organizationId: string; metric: string; enabled: boolean; limit: number; remaining: number}) => {
        Logger.log(`${quota.organizationId.padEnd(longestOrganization, ' ')} ${quota.metric.padEnd(longestMetric, ' ')} ${(quota.enabled ? 'Y': 'N').padEnd(8, ' ')} ${quota.limit.toString().padEnd(6, ' ')} ${quota.remaining.toString().padEnd(10, ' ')}`);
      });
    }
  });

const longestQuotaMetric = (quotas: {metric: string}[]) => {
  return quotas.reduce((max, quota) => {
    return Math.max(max, quota.metric.length);
  }, 0);
}

const longestQuotaOrganization = (quotas: {organizationId: string}[]) => {
  return quotas.reduce((max, quota) => {
    return Math.max(max, quota.organizationId.length);
  }, 0);
}
