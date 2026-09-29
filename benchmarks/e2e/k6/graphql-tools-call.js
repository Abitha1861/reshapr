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
 * End-to-end MCP `tools/call` load script against a running reshapr proxy (GraphQL backend).
 *
 * Counterpart of `rest-tools-call.js` for a GraphQL exposition: it targets the `user` query tool
 * of the GitHub GraphQL schema served by `E2EGraphQLBenchEnvironment`. The three phases (warmup /
 * latency / throughput) and the reported metrics are identical to the REST script so the results
 * are directly comparable.
 *
 * The call uses the modern stateless MCP mode (protocol 2026-07-28: `MCP-Protocol-Version` header
 * + `Mcp-Method`/`Mcp-Name` mirror headers + `params._meta` envelope) so that no session
 * management is needed in the injector.
 *
 * Environment variables:
 *   BASE_URL  proxy base URL                       (default http://localhost:7777)
 *   PAYLOAD   small | medium | large               (default small) -> exposition bench-graphql-<PAYLOAD>
 *   PHASE     warmup | latency | throughput        (default latency)
 *   RATE      arrival rate in req/s (latency)      (default 150)
 *   VUS       virtual users (throughput)           (default 64)
 *   DURATION  phase duration                       (default 30s, warmup 15s)
 *   TOOL      GraphQL query tool name              (default user)
 */
import http from 'k6/http';
import { check } from 'k6';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:7777';
const PAYLOAD = (__ENV.PAYLOAD || 'small').toLowerCase();
const PHASE = (__ENV.PHASE || 'latency').toLowerCase();
const RATE = parseInt(__ENV.RATE || '150', 10);
const VUS = parseInt(__ENV.VUS || '64', 10);
const DURATION = __ENV.DURATION || (PHASE === 'warmup' ? '15s' : '30s');
const TOOL = __ENV.TOOL || 'user';

const SCENARIOS = {
  warmup: {
    executor: 'constant-vus',
    vus: Math.min(VUS, 16),
    duration: DURATION,
  },
  latency: {
    executor: 'constant-arrival-rate',
    rate: RATE,
    timeUnit: '1s',
    duration: DURATION,
    preAllocatedVUs: Math.max(50, RATE),
    maxVUs: Math.max(200, RATE * 2),
  },
  throughput: {
    executor: 'constant-vus',
    vus: VUS,
    duration: DURATION,
  },
};

export const options = {
  scenarios: { [PHASE]: SCENARIOS[PHASE] },
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
  thresholds: {
    checks: ['rate>0.99'],
  },
  discardResponseBodies: false,
};

const MCP_ENDPOINT = `${BASE_URL}/mcp/bench-graphql-${PAYLOAD}`;

// tools/call against the GitHub 'user' query. The arguments mirror the GraphQL micro-benchmark:
// a scalar 'login' plus two relation selectors (__relation_* prefix) exercised by the converter.
const BODY = JSON.stringify({
  jsonrpc: '2.0',
  id: 1,
  method: 'tools/call',
  params: {
    name: TOOL,
    arguments: {
      login: 'octocat',
      __relation_avatarUrl: { size: 32 },
      __relation_followers: { last: 10 },
    },
    _meta: { 'io.modelcontextprotocol/protocolVersion': '2026-07-28' },
  },
});

const PARAMS = {
  headers: {
    'Content-Type': 'application/json',
    Accept: 'application/json',
    'MCP-Protocol-Version': '2026-07-28',
    'Mcp-Method': 'tools/call',
    'Mcp-Name': TOOL,
    'User-Agent': 'reshapr-e2e-bench/k6',
  },
};

export default function () {
  const res = http.post(MCP_ENDPOINT, BODY, PARAMS);
  check(res, {
    'status is 200': (r) => r.status === 200,
    'jsonrpc result': (r) => r.body != null && r.body.indexOf('"result"') !== -1
        && r.body.indexOf('"isError":false') !== -1,
  });
}
