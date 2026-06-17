#!/usr/bin/env node

/**
 * Sparrow Phase 2 — 多端点混合压测引擎
 *
 * 目标:1000 QPS,P99 < 300ms(ROADMAP Phase 2 验收线)
 *
 * 端点混合(模拟真实流量分布):
 *   40% GET  /api/graph/tree          — Redis 缓存读(命中后极快)
 *   15% GET  /api/graph/overview      — Redis 缓存读
 *   10% GET  /api/graph/search?q=xxx  — MySQL 全文检索
 *   10% GET  /api/graph/node/{id}     — Neo4j 读
 *    8% GET  /api/graph/nodes         — MySQL 分页
 *    7% POST /api/user/login          — MySQL 写+Redis 写
 *    5% GET  /api/trade/products      — 静态返回
 *    3% GET  /api/trade/orders        — 鉴权+MySQL 读
 *    2% POST /api/ai/ask              — Sentinel 限流(预期部分 429)
 *
 * 用法:
 *   node phase2-load.mjs --qps 1000 --duration 60 --p99 300
 *   node phase2-load.mjs --qps 1000 --duration 60 --tokens ../tokens.json
 */

import { readFileSync, existsSync } from "fs";
import { join, dirname } from "path";
import { fileURLToPath } from "url";

const __dirname = dirname(fileURLToPath(import.meta.url));

// ── 参数 ──
const defaults = { baseUrl: "http://localhost:8080", qps: 1000, duration: 60, p99: 300, tokens: "", rampup: 5 };
const opts = parseArgs(process.argv.slice(2));
const baseUrl = (opts.baseUrl || defaults.baseUrl).replace(/\/$/, "");
const targetQps = Number(opts.qps || defaults.qps);
const durationSeconds = Number(opts.duration || defaults.duration);
const p99Limit = Number(opts.p99 || defaults.p99);
const rampUpSeconds = Number(opts.rampup || defaults.rampup);
const tokensFile = opts.tokens
  ? opts.tokens
  : join(__dirname, "..", "tokens.json");

// ── Token 池 ──
let TOKENS = [];
if (existsSync(tokensFile)) {
  TOKENS = JSON.parse(readFileSync(tokensFile, "utf8"));
}
function randomToken() {
  if (TOKENS.length === 0) return null;
  return TOKENS[Math.floor(Math.random() * TOKENS.length)]?.token || null;
}

// ── 端点定义 ──
const SEARCH_TERMS = ["蒸汽机", "互联网", "电力", "计算机", "火", "轮子", "印刷术", "钢铁", "半导体", "AI"];
const NODE_IDS = [1, 5, 10, 20, 30, 41, 50, 60, 65, 70, 77];

const ENDPOINTS = [
  { weight: 40, fn: () => get("/api/graph/tree") },
  { weight: 15, fn: () => get("/api/graph/overview") },
  { weight: 10, fn: () => get(`/api/graph/search?q=${pick(SEARCH_TERMS)}&limit=10`) },
  { weight: 10, fn: () => get(`/api/graph/node/${pick(NODE_IDS)}`) },
  { weight:  8, fn: () => get(`/api/graph/nodes?page=1&size=20`) },
  { weight:  7, fn: () => loginRandom() },
  { weight:  5, fn: () => get("/api/trade/products") },
  { weight:  3, fn: () => getAuthed("/api/trade/orders") },
  { weight:  2, fn: () => aiAsk() },
];

// 归一化权重为累积区间
const weightTotal = ENDPOINTS.reduce((s, e) => s + e.weight, 0);
const cumulative = [];
let acc = 0;
for (const ep of ENDPOINTS) {
  acc += ep.weight;
  cumulative.push(acc / weightTotal);
}

function pickEndpoint() {
  const r = Math.random();
  for (let i = 0; i < cumulative.length; i++) {
    if (r <= cumulative[i]) return ENDPOINTS[i].fn;
  }
  return ENDPOINTS[0].fn;
}

function pick(arr) { return arr[Math.floor(Math.random() * arr.length)]; }

// ── HTTP 工具 ──
async function get(path, token) {
  const headers = { accept: "application/json" };
  if (token) headers["Authorization"] = `Bearer ${token}`;
  const resp = await fetch(baseUrl + path, { headers, signal: AbortSignal.timeout(5000) });
  return { status: resp.status, body: await resp.json() };
}

async function getAuthed(path) {
  const token = randomToken();
  if (!token) return { status: 0, body: { code: -1 }, skipped: true };
  return get(path, token);
}

async function loginRandom() {
  // 随机选一个种子用户登录
  const idx = Math.floor(1 + Math.random() * Math.min(TOKENS.length || 10000, 10000));
  const username = `loaduser_${String(idx).padStart(8, "0")}`;
  const resp = await fetch(baseUrl + "/api/user/login", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ username, password: "test123456" }),
    signal: AbortSignal.timeout(5000),
  });
  return { status: resp.status, body: await resp.json() };
}

async function aiAsk() {
  const token = randomToken();
  if (!token) return { status: 0, body: { code: -1 }, skipped: true };
  const resp = await fetch(baseUrl + "/api/ai/ask", {
    method: "POST",
    headers: { "Content-Type": "application/json", Authorization: `Bearer ${token}` },
    body: JSON.stringify({ question: "蒸汽机需要什么前置技术?" }),
    signal: AbortSignal.timeout(15000),
  });
  return { status: resp.status, body: await resp.json() };
}

// ── 压测引擎 ──
const latencies = [];
const perEndpoint = {};  // { label: { count, p50, p95, p99 } }
let failed = 0;
let rateLimited = 0;
let skipped = 0;
const endpointLatencies = {}; // { label: number[] }

function recordLatency(label, ms, status, body) {
  latencies.push(ms);
  if (!endpointLatencies[label]) endpointLatencies[label] = [];
  endpointLatencies[label].push(ms);

  if (status >= 500 || (body && body.code !== 0 && body.code !== 429)) {
    failed++;
  } else if (status === 429 || (body && body.code === 429)) {
    rateLimited++;
  }
}

async function fireOne() {
  const fn = pickEndpoint();
  const start = performance.now();
  const label = fn.name || "unknown";
  try {
    const result = await fn();
    const ms = performance.now() - start;
    if (result?.skipped) {
      skipped++;
    } else {
      recordLatency(label, ms, result?.status || 0, result?.body);
    }
  } catch {
    failed++;
    const ms = performance.now() - start;
    endpointLatencies[label] = endpointLatencies[label] || [];
    endpointLatencies[label].push(ms);
  }
}

// ── Ramp-up:在前 rampUpSeconds 秒内线性增加 QPS ──
function currentQps(elapsed) {
  if (elapsed >= rampUpSeconds) return targetQps;
  return Math.max(1, Math.floor(targetQps * (elapsed / rampUpSeconds)));
}

// ── 主流程 ──
console.log("═══════════════════════════════════════════════");
console.log("  Sparrow Phase 2 Load Test");
console.log("═══════════════════════════════════════════════");
console.log(`  target:    ${targetQps} QPS`);
console.log(`  duration:  ${durationSeconds}s (ramp-up ${rampUpSeconds}s)`);
console.log(`  p99 limit: ${p99Limit}ms`);
console.log(`  base URL:  ${baseUrl}`);
console.log(`  tokens:    ${TOKENS.length} available`);
console.log(`  endpoints: ${ENDPOINTS.length} (weighted)`);
console.log("═══════════════════════════════════════════════\n");

// Warmup
console.log("[warmup] GET /api/graph/tree ...");
try {
  const w = await get("/api/graph/tree");
  if (w.body?.code !== 0) throw new Error(`code=${w.body?.code}`);
  console.log("[warmup] OK\n");
} catch (e) {
  console.error(`[warmup] FAIL: ${e.message}`);
  console.error("请确认服务已启动:docker compose up -d --build");
  process.exit(1);
}

// 压测循环:每 100ms 一个 tick,按当前 QPS 发射请求
const startTime = performance.now();
const pending = [];
let tickCount = 0;

const tickInterval = setInterval(() => {
  const elapsed = (performance.now() - startTime) / 1000;
  if (elapsed >= durationSeconds) {
    clearInterval(tickInterval);
    return;
  }

  const qps = currentQps(elapsed);
  const perTick = Math.max(1, Math.ceil(qps / 10)); // 10 ticks/sec

  for (let i = 0; i < perTick; i++) {
    pending.push(fireOne());
  }
  tickCount++;
}, 100);

// 等待所有请求完成
await new Promise((resolve) => {
  const check = setInterval(() => {
    const elapsed = (performance.now() - startTime) / 1000;
    if (elapsed >= durationSeconds && pending.length === 0) {
      clearInterval(check);
      resolve();
    } else if (elapsed >= durationSeconds + 30) {
      // 超时保护
      clearInterval(check);
      resolve();
    }
  }, 500);
});

// 确保最后一批完成
await Promise.allSettled(pending);

const totalElapsed = (performance.now() - startTime) / 1000;

// ── 结果统计 ──
function percentile(values, pct) {
  if (values.length === 0) return Infinity;
  const sorted = [...values].sort((a, b) => a - b);
  const idx = Math.min(sorted.length - 1, Math.ceil((pct / 100) * sorted.length) - 1);
  return sorted[idx];
}

latencies.sort((a, b) => a - b);
const total = latencies.length;
const p50 = percentile(latencies, 50);
const p95 = percentile(latencies, 95);
const p99 = percentile(latencies, 99);
const achievedQps = total / totalElapsed;

console.log("═══════════════════════════════════════════════");
console.log("  RESULTS");
console.log("═══════════════════════════════════════════════");
console.log(`  total:       ${total} requests`);
console.log(`  elapsed:     ${totalElapsed.toFixed(1)}s`);
console.log(`  achieved:    ${achievedQps.toFixed(1)} QPS (target ${targetQps})`);
console.log(`  success:     ${total - failed - rateLimited}`);
console.log(`  failed:      ${failed}`);
console.log(`  rate-limited: ${rateLimited} (429)`);
console.log(`  skipped:     ${skipped} (no token)`);
console.log();
console.log(`  p50: ${p50.toFixed(1)}ms`);
console.log(`  p95: ${p95.toFixed(1)}ms`);
console.log(`  p99: ${p99.toFixed(1)}ms  (limit ${p99Limit}ms)`);
console.log();

// 按端点统计
console.log("  Per-endpoint breakdown:");
console.log("  ────────────────────────────────────────────");
for (const [label, vals] of Object.entries(endpointLatencies)) {
  const ep50 = percentile(vals, 50);
  const ep95 = percentile(vals, 95);
  const ep99 = percentile(vals, 99);
  console.log(
    `  ${label.padEnd(24)} n=${String(vals.length).padStart(6)}  ` +
    `p50=${ep50.toFixed(0).padStart(5)}ms  ` +
    `p95=${ep95.toFixed(0).padStart(5)}ms  ` +
    `p99=${ep99.toFixed(0).padStart(5)}ms`
  );
}
console.log();

// ── 判定 ──
let pass = true;
const reasons = [];

// 1. P99 达标(排除 AI 429)
if (p99 > p99Limit) {
  pass = false;
  reasons.push(`p99 ${p99.toFixed(1)}ms > ${p99Limit}ms`);
}

// 2. 达成 QPS >= 95% 目标
if (achievedQps < targetQps * 0.95) {
  pass = false;
  reasons.push(`achieved QPS ${achievedQps.toFixed(1)} < 95% of ${targetQps}`);
}

// 3. 失败率 < 1%(不含 429)
const failRate = failed / Math.max(total, 1);
if (failRate > 0.01) {
  pass = false;
  reasons.push(`failure rate ${(failRate * 100).toFixed(2)}% > 1%`);
}

console.log("═══════════════════════════════════════════════");
if (pass) {
  console.log(`  ✅ PASS: ${targetQps} QPS, P99 ${p99.toFixed(1)}ms ≤ ${p99Limit}ms`);
} else {
  console.log("  ❌ FAIL:");
  for (const r of reasons) console.log(`     • ${r}`);
}
console.log("═══════════════════════════════════════════════\n");

// ── 函数工具 ──
function parseArgs(argv) {
  const parsed = {};
  for (let i = 0; i < argv.length; i++) {
    const arg = argv[i];
    if (!arg.startsWith("--")) continue;
    const key = arg.slice(2).replace(/-([a-z])/g, (_, ch) => ch.toUpperCase());
    parsed[key] = argv[i + 1];
    i++;
  }
  return parsed;
}

process.exit(pass ? 0 : 1);
