#!/usr/bin/env node

const defaults = {
  baseUrl: 'http://localhost:8080',
  qps: 50,
  durationSeconds: 30,
  p99Ms: 200
};

const options = parseArgs(process.argv.slice(2));
const baseUrl = (options.baseUrl || defaults.baseUrl).replace(/\/$/, '');
const qps = Number(options.qps || defaults.qps);
const durationSeconds = Number(options.duration || defaults.durationSeconds);
const p99Limit = Number(options.p99 || defaults.p99Ms);
const total = Math.max(1, Math.floor(qps * durationSeconds));
const intervalMs = 1000 / qps;
const latencies = [];
let failed = 0;

await warmup();

const startedAt = performance.now();
const requests = [];
for (let i = 0; i < total; i++) {
  requests.push(schedule(i * intervalMs, hitTree));
}
await Promise.all(requests);
const elapsedSeconds = (performance.now() - startedAt) / 1000;

latencies.sort((a, b) => a - b);
const p50 = percentile(latencies, 50);
const p95 = percentile(latencies, 95);
const p99 = percentile(latencies, 99);
const achievedQps = latencies.length / elapsedSeconds;

console.log(`Phase 1 load result`);
console.log(`baseUrl=${baseUrl}`);
console.log(`target=${qps} qps, duration=${durationSeconds}s, requests=${total}`);
console.log(`success=${latencies.length}, failed=${failed}, achievedQps=${achievedQps.toFixed(2)}`);
console.log(`p50=${p50.toFixed(1)}ms, p95=${p95.toFixed(1)}ms, p99=${p99.toFixed(1)}ms`);

if (failed > 0) {
  console.error(`FAIL: ${failed} requests failed`);
  process.exit(1);
}
if (p99 > p99Limit) {
  console.error(`FAIL: p99 ${p99.toFixed(1)}ms > ${p99Limit}ms`);
  process.exit(1);
}
if (achievedQps < qps * 0.95) {
  console.error(`FAIL: achieved QPS ${achievedQps.toFixed(2)} is below 95% of target ${qps}`);
  process.exit(1);
}

console.log(`PASS: p99 <= ${p99Limit}ms at ~${qps} QPS`);

async function warmup() {
  const resp = await fetchJson('/api/graph/tree');
  if (!resp || resp.code !== 0 || !resp.data || !Array.isArray(resp.data.nodes)) {
    throw new Error('warmup failed: /api/graph/tree did not return a valid ApiResponse');
  }
}

async function hitTree() {
  const start = performance.now();
  try {
    const resp = await fetchJson('/api/graph/tree');
    const ms = performance.now() - start;
    if (resp && resp.code === 0) {
      latencies.push(ms);
    } else {
      failed++;
    }
  } catch {
    failed++;
  }
}

async function fetchJson(path) {
  const response = await fetch(baseUrl + path, {
    headers: { accept: 'application/json' },
    signal: AbortSignal.timeout(5000)
  });
  return response.json();
}

function schedule(delayMs, fn) {
  return new Promise(resolve => {
    setTimeout(() => {
      fn().finally(resolve);
    }, delayMs);
  });
}

function percentile(values, pct) {
  if (values.length === 0) return Number.POSITIVE_INFINITY;
  const index = Math.min(values.length - 1, Math.ceil((pct / 100) * values.length) - 1);
  return values[index];
}

function parseArgs(args) {
  const parsed = {};
  for (let i = 0; i < args.length; i++) {
    const arg = args[i];
    if (!arg.startsWith('--')) continue;
    const key = arg.slice(2).replace(/-([a-z])/g, (_, ch) => ch.toUpperCase());
    parsed[key] = args[i + 1];
    i++;
  }
  return parsed;
}
