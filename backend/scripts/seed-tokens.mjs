/**
 * Sparrow Phase 2 — 批量注册/登录用户并收集 token,供压测使用。
 *
 * 用法:
 *   node seed-tokens.mjs --base-url http://localhost:8080 --count 10000 --concurrency 20
 *
 * 输出:
 *   tokens.json — JSON 数组,每个元素 { token, userId, username }
 *
 * 策略:
 *   1. 先尝试用 loaduser_XXXX 登录(SQL 种子用户的用户名)
 *   2. 登录失败则注册(首次压测准备)
 *   3. 并发控制:默认 20 并发,避免打爆 gateway
 */

import { writeFileSync, existsSync, readFileSync } from "fs";
import { join, dirname } from "path";
import { fileURLToPath } from "url";

const __dirname = dirname(fileURLToPath(import.meta.url));

const args = parseArgs(process.argv.slice(2));
const BASE_URL = args["base-url"] || "http://localhost:8080";
// Phase 3 默认池 50000 / 并发 50,匹配 10k QPS 下 authed 端点所需身份多样性。
const COUNT = parseInt(args["count"] || "50000", 10);
const CONCURRENCY = parseInt(args["concurrency"] || "50", 10);

const OUT_FILE = join(__dirname, "..", "tokens.json");

function parseArgs(argv) {
  const out = {};
  for (let i = 0; i < argv.length; i += 2) {
    const k = argv[i]?.replace(/^--/, "");
    out[k] = argv[i + 1];
  }
  return out;
}

async function login(username, password) {
  const resp = await fetch(`${BASE_URL}/api/user/login`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ username, password }),
    signal: AbortSignal.timeout(5000),
  });
  return resp.json();
}

async function register(username, password) {
  const resp = await fetch(`${BASE_URL}/api/user/register`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ username, password }),
    signal: AbortSignal.timeout(5000),
  });
  return resp.json();
}

async function getToken(idx) {
  const username = `loaduser_${String(idx).padStart(8, "0")}`;
  const password = "test123456";

  // 先尝试登录(SQL 种子用户)
  try {
    const body = await login(username, password);
    if (body.code === 0 && body.data?.token) {
      return { token: body.data.token, username };
    }
  } catch {
    // 网络错误等,尝试注册
  }

  // 登录失败则注册(首次运行)
  const body = await register(username, password);
  if (body.code === 0 && body.data?.token) {
    return { token: body.data.token, username };
  }

  throw new Error(`Failed to get token for ${username}: ${JSON.stringify(body)}`);
}

async function main() {
  console.log(`[seed-tokens] base=${BASE_URL} count=${COUNT} concurrency=${CONCURRENCY}`);

  // 检查已有 token(断点续传)
  let existing = [];
  if (existsSync(OUT_FILE)) {
    try {
      existing = JSON.parse(readFileSync(OUT_FILE, "utf8"));
      console.log(`[seed-tokens] 已有 ${existing.length} 个 token`);
    } catch { /* ignore */ }
  }

  const tokens = [...existing];
  let success = 0;
  let failed = 0;
  const startTime = Date.now();

  // 并发池
  let nextIdx = existing.length + 1;
  const queue = [];

  for (let i = 0; i < CONCURRENCY; i++) {
    queue.push(worker());
  }

  async function worker() {
    while (nextIdx <= COUNT) {
      const idx = nextIdx++;
      try {
        const result = await getToken(idx);
        tokens.push(result);
        success++;
        if (success % 500 === 0) {
          console.log(`[seed-tokens] 进度: ${success}/${COUNT} (${(success * 100 / COUNT).toFixed(1)}%)`);
          // 定期保存(断点续传)
          writeFileSync(OUT_FILE, JSON.stringify(tokens));
        }
      } catch (e) {
        failed++;
        if (failed <= 10) console.error(`[seed-tokens] 失败 #${idx}: ${e.message}`);
      }
    }
  }

  await Promise.all(queue);

  writeFileSync(OUT_FILE, JSON.stringify(tokens));

  const elapsed = ((Date.now() - startTime) / 1000).toFixed(1);
  console.log(`\n[seed-tokens] 完成: ${success} 成功, ${failed} 失败, 耗时 ${elapsed}s`);
  console.log(`[seed-tokens] 输出: ${OUT_FILE}`);

  process.exit(failed > success * 0.05 ? 1 : 0);
}

main();
