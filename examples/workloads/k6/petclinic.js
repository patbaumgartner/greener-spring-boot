/**
 * k6 load test script for Spring Petclinic
 *
 * k6 is a developer-centric load testing tool written in Go, with test scripts
 * in JavaScript/TypeScript.  It supports constant arrival-rate scenarios
 * (constant-vus, ramping-arrival-rate) that are ideal for reproducible energy
 * measurement because the workload is independent of server response time.
 *
 * Usage via greener-spring-boot:
 *   <externalTrainingScriptFile>examples/workloads/k6/run.sh</externalTrainingScriptFile>
 *
 * Standalone:
 *   APP_URL=http://localhost:8080 WARMUP_SECONDS=30 MEASURE_SECONDS=60 RPS=20 \
 *     k6 run examples/workloads/k6/petclinic.js
 *
 * Install k6:
 *   Linux:  https://dl.k6.io/deb/repo/gpg-key.pub | sudo apt-key add -
 *           echo "deb https://dl.k6.io/deb stable main" | sudo tee /etc/apt/sources.list.d/k6.list
 *           sudo apt-get update && sudo apt-get install k6
 *   macOS:  brew install k6
 *   Docker: docker run --rm -i grafana/k6 run -
 *   Docs:   https://grafana.com/docs/k6/latest/
 */

import http from 'k6/http';
import { sleep, check } from 'k6';

// ── Configuration ─────────────────────────────────────────────────────────────
// All values come from environment variables set by the greener-spring-boot plugin.
const BASE_URL = __ENV.APP_URL || 'http://localhost:8080';
const WARMUP_SECONDS = parseInt(__ENV.WARMUP_SECONDS || '30', 10);
const MEASURE_SECONDS = parseInt(__ENV.MEASURE_SECONDS || '60', 10);
const RPS = parseInt(__ENV.RPS || '20', 10);

// URLs exercised during the test
const ENDPOINTS = [
  `${BASE_URL}/`,
  `${BASE_URL}/owners`,
  `${BASE_URL}/owners?lastName=`,
  `${BASE_URL}/vets.html`,
  `${BASE_URL}/actuator/health`,
];

// ── Scenario definition ───────────────────────────────────────────────────────
//
// Up to two consecutive scenarios run in sequence:
//   1. warmup   — constant-rate load, no energy recorded (Joular Core not yet active)
//   2. measure  — constant-rate load, Joular Core records energy
//
// The plugin calls the script twice: once for warmup (MEASURE_SECONDS=0) and once
// for measurement (WARMUP_SECONDS=0).  Scenarios with duration 0 are omitted so
// that k6 does not reject a "0s" duration.
const scenarios = {};
if (WARMUP_SECONDS > 0) {
  scenarios.warmup = {
    executor: 'constant-arrival-rate',
    duration: `${WARMUP_SECONDS}s`,
    rate: RPS,
    timeUnit: '1s',
    preAllocatedVUs: Math.max(2, Math.ceil(RPS / 5)),
    maxVUs: Math.max(10, RPS),
    gracefulStop: '5s',
  };
}
if (MEASURE_SECONDS > 0) {
  scenarios.measure = {
    executor: 'constant-arrival-rate',
    startTime: WARMUP_SECONDS > 0 ? `${WARMUP_SECONDS}s` : '0s',
    duration: `${MEASURE_SECONDS}s`,
    rate: RPS,
    timeUnit: '1s',
    preAllocatedVUs: Math.max(2, Math.ceil(RPS / 5)),
    maxVUs: Math.max(10, RPS),
    gracefulStop: '5s',
  };
}

export const options = {
  scenarios,
  thresholds: {
    http_req_failed: ['rate<0.05'],        // <5% error rate
    http_req_duration: ['p(95)<2000'],     // 95th percentile < 2 s
  },
};

// ── Virtual user script ───────────────────────────────────────────────────────
let counter = 0;

export default function () {
  const url = ENDPOINTS[counter % ENDPOINTS.length];
  counter++;

  const res = http.get(url, { timeout: '10s' });

  check(res, {
    'status is 2xx': (r) => r.status >= 200 && r.status < 300,
  });

  // Small think-time to avoid thundering-herd at low RPS
  sleep(0.05);
}
