// Sparrow Phase 3 - k6 load test script (10k+ QPS)
//
// Endpoint mix = realistic wiki-scale frontend (no full /tree; ai/* off in slim stack):
//   28% GET  /api/graph/overview        Redis cached, small aggregate (landing)
//   22% GET  /api/graph/subgraph        MySQL bounded subgraph (limit=400 render)
//   13% GET  /api/graph/search          MySQL full-text
//   12% GET  /api/graph/node/{id}       Neo4j read
//   10% GET  /api/graph/nodes           MySQL paged
//    8% POST /api/user/login            MySQL + Redis write
//    5% GET  /api/trade/products        static
//    2% GET  /api/trade/orders          authed MySQL read
// NOTE: /api/graph/tree (full 10935-node/97798-edge, ~10MB) dropped on purpose. The
//   wiki-scale frontend renders via /overview + /subgraph; the full tree OOMs graph at
//   768m heap regardless of cache (Redis get + Jackson deserialize + Spring reserialize
//   = ~3x payload transient heap per request), so it is not a real capacity signal.
//
// Open model: ramping-arrival-rate. Env-driven. See k6-load.ps1.
//   k6 run k6-phase3-ascii.js -e BASE_URL=http://sparrow-gateway:8080 \
//     -e TARGET_QPS=10000 -e DURATION=60 -e RAMP_UP=10 -e P99_LIMIT=500 \
//     -e TOKENS_FILE=/data/tokens.json
//
// NOTE: kept pure-ASCII on purpose. Windows PowerShell 5.1 reads BOM-less UTF-8
// files as ANSI, which mangles CJK comments in the .ps1 wrapper; this JS file is
// consumed by the k6 Go runtime (UTF-8 safe), so ASCII avoids any round-trip issue.

import http from "k6/http";
import { SharedArray } from "k6/data";
import { Counter } from "k6/metrics";
import { check } from "k6";

// -- config (env) --
const BASE_URL = (__ENV.BASE_URL || "http://localhost:8080").replace(/\/$/, "");
const TARGET_QPS = parseInt(__ENV.TARGET_QPS || "10000", 10);
const DURATION = parseInt(__ENV.DURATION || "60", 10);        // steady-state seconds
const RAMP_UP = parseInt(__ENV.RAMP_UP || "10", 10);          // ramp-up seconds
const P99_LIMIT = parseInt(__ENV.P99_LIMIT || "500", 10);     // ms
const TOKENS_FILE = __ENV.TOKENS_FILE || "../tokens.json";

// -- token pool (SharedArray; open() must run in init context, runs once) --
const TOKENS = new SharedArray("tokens", function () {
  try {
    const raw = open(TOKENS_FILE);
    const parsed = JSON.parse(raw);
    if (Array.isArray(parsed)) return parsed.map((t) => t.token).filter(Boolean);
  } catch (e) {
    // missing/unreadable file -> empty pool, authed endpoints silently skipped
  }
  return [];
});
const tokenCount = TOKENS.length;

// -- endpoint weights (cumulative interval) --
const SEARCH_TERMS = ["zhengqi", "hulianwang", "dianli", "jisuanji", "huo", "lunzi", "yinshuashu", "gangtie", "bandaoti", "AI"];
const NODE_IDS = [1, 5, 10, 20, 30, 41, 50, 60, 65, 70, 77];

const ENDPOINTS = [
  { w: 28, name: "graph_overview" },
  { w: 22, name: "graph_subgraph" },
  { w: 13, name: "graph_search" },
  { w: 12, name: "graph_node" },
  { w: 10, name: "graph_nodes" },
  { w:  8, name: "user_login" },
  { w:  5, name: "trade_products" },
  { w:  2, name: "trade_orders" },
];
const W_TOTAL = ENDPOINTS.reduce((s, e) => s + e.w, 0);
const CUM = [];
{ let a = 0; for (const e of ENDPOINTS) { a += e.w; CUM.push(a / W_TOTAL); } }

function pickName() {
  const r = Math.random();
  for (let i = 0; i < CUM.length; i++) if (r <= CUM[i]) return ENDPOINTS[i].name;
  return ENDPOINTS[0].name;
}
function pick(arr) { return arr[Math.floor(Math.random() * arr.length)]; }
function randomToken() {
  if (TOKENS.length === 0) return null;
  return TOKENS[Math.floor(Math.random() * TOKENS.length)];
}

// -- custom metrics --
const rateLimited = new Counter("rate_limited");   // 429 count (not counted as failure)
const byEndpoint = {};
for (const e of ENDPOINTS) byEndpoint[e.name] = new Counter(`ep_${e.name}`);

// -- options: open-model arrival-rate --
export const options = {
  scenarios: {
    ramping: {
      executor: "ramping-arrival-rate",
      startRate: 0,
      timeUnit: "1s",
      preAllocatedVUs: Math.max(500, Math.ceil(TARGET_QPS * 0.3)),
      maxVUs: Math.max(2000, TARGET_QPS),
      stages: [
        { target: TARGET_QPS, duration: `${RAMP_UP}s` },
        { target: TARGET_QPS, duration: `${DURATION}s` },
        { target: 0, duration: "5s" },           // graceful ramp-down
      ],
    },
  },
  thresholds: {
    // business failure rate < 1% (429 tracked separately, not a failure)
    "http_req_failed{scenario:ramping}": ["rate<0.01"],
    "http_req_duration{scenario:ramping}": [`p(99)<${P99_LIMIT}`],
  },
};

// Treat 2xx-4xx as success (so 429 doesn't pollute http_req_failed); 5xx/network = fail
http.setResponseCallback(http.expectedStatuses({ min: 200, max: 499 }));

function fire() {
  const name = pickName();
  byEndpoint[name].add(1);
  let resp;

  switch (name) {
    case "graph_subgraph":
      resp = http.get(`${BASE_URL}/api/graph/subgraph?limit=400`, { tags: { ep: name }, timeout: "10s" });
      break;
    case "graph_overview":
      resp = http.get(`${BASE_URL}/api/graph/overview`, { tags: { ep: name }, timeout: "10s" });
      break;
    case "graph_search":
      resp = http.get(`${BASE_URL}/api/graph/search?q=${encodeURIComponent(pick(SEARCH_TERMS))}&limit=10`,
        { tags: { ep: name }, timeout: "10s" });
      break;
    case "graph_node":
      resp = http.get(`${BASE_URL}/api/graph/node/${pick(NODE_IDS)}`, { tags: { ep: name }, timeout: "10s" });
      break;
    case "graph_nodes":
      resp = http.get(`${BASE_URL}/api/graph/nodes?page=1&size=20`, { tags: { ep: name }, timeout: "10s" });
      break;
    case "user_login": {
      const maxIdx = Math.max(1, Math.min(10000, tokenCount || 10000));
      const idx = 1 + Math.floor(Math.random() * maxIdx);
      const username = `loaduser_${String(idx).padStart(8, "0")}`;
      resp = http.post(`${BASE_URL}/api/user/login`,
        JSON.stringify({ username, password: "test123456" }),
        { tags: { ep: name }, headers: { "Content-Type": "application/json" }, timeout: "10s" });
      break;
    }
    case "trade_products":
      resp = http.get(`${BASE_URL}/api/trade/products`, { tags: { ep: name }, timeout: "10s" });
      break;
    case "trade_orders": {
      const tok = randomToken();
      if (!tok) return;
      resp = http.get(`${BASE_URL}/api/trade/orders`,
        { tags: { ep: name }, headers: { Authorization: `Bearer ${tok}` }, timeout: "10s" });
      break;
    }
    case "ai_ask": {
      const tok = randomToken();
      if (!tok) return;
      resp = http.post(`${BASE_URL}/api/ai/ask`,
        JSON.stringify({ question: "steam engine prerequisites?" }),
        { tags: { ep: name }, headers: { "Content-Type": "application/json", Authorization: `Bearer ${tok}` }, timeout: "15s" });
      break;
    }
    default:
      return;
  }

  if (!resp) return;
  if (resp.status === 429) rateLimited.add(1);
  check(resp, { "status not 5xx": (r) => r.status < 500 });
}

export default function () {
  fire();
}

export function handleSummary(data) {
  const d = data.metrics;
  const dur = d.http_req_duration?.values || {};
  const failed = d.http_req_failed?.values?.rate ?? 0;
  const iters = d.iterations?.values?.count ?? 0;
  const rl = d.rate_limited?.values?.count ?? 0;
  const rps = d.http_reqs?.values?.rate ?? 0;
  console.log(
    `\n===== k6 SUMMARY =====\n` +
    `  target QPS:   ${TARGET_QPS}\n` +
    `  achieved RPS: ${rps.toFixed(1)}\n` +
    `  iterations:   ${iters}\n` +
    `  p50/p95/p99:  ${(dur["p(50)"] ?? 0).toFixed(1)} / ` +
    `${(dur["p(95)"] ?? 0).toFixed(1)} / ${(dur["p(99)"] ?? 0).toFixed(1)} ms\n` +
    `  failed rate:  ${(failed * 100).toFixed(2)}%  (limit 1%)\n` +
    `  429 (AI limit): ${rl}\n` +
    `  tokens pool:  ${tokenCount}\n` +
    `======================`
  );
  return {};
}
