# SparrowSpider · 人类科技树知识爬虫

## 在 Sparrow 应用内跑完整闭环

本目录已经被当前 `Sparrow` 仓库接管为 `spider` compose profile。默认闭环是:

```text
MediaWiki 抓取 -> 知识抽取 -> 写入 sparrow_graph.tech_node/tech_edge
-> 写入 sparrow.rag_document -> POST sparrow-graph /internal/graph/import
-> MySQL 重建 Neo4j -> 清图谱缓存 -> 发布 GraphChangedEvent 给 RAG
```

运行一次默认 20 条:

```powershell
docker compose --profile spider run --rm sparrow-spider
```

调整数量:

```powershell
$env:SPIDER_LIMIT=50
docker compose --profile spider run --rm sparrow-spider
```

AI/RAG 默认随 Sparrow 启动;需要在线消费重建事件时,先启动默认服务,再运行 spider:

```powershell
docker compose up -d --build
docker compose --profile spider run --rm sparrow-spider
```

可继续覆盖 `SPIDER_PROXY`、`SPIDER_WIKI_API`、`AI_BASE_URL`、`AI_API_KEY`、`AI_CHAT_MODEL` 等变量。

为 [Sparrow](../Sparrow) 持续供给科技树知识的独立爬虫项目,架构参考
[MindSpider](https://github.com/666ghj/MindSpider) 的"两阶段 + LLM"形态:

| MindSpider | SparrowSpider 对应 |
|---|---|
| BroadTopicExtraction 热点话题发现 | **topic_discovery** 技术词条发现(种子词表 + 内链扩展) |
| DeepSentimentCrawling 多平台深爬 | **deep_crawling** MediaWiki API 限速爬取 |
| 情感分析 | **knowledge_extraction** LLM 抽取时代/年代/摘要/**依赖边** |
| MySQL 入库 | **storage** + **export** 导出 Sparrow 可直接消费的产物 |

## 流水线

```
种子词表(77 个 Sparrow 节点 + 40 个扩展词)
        │  --init-seeds
        ▼
tech_candidate(PENDING)
        │  --crawl      MediaWiki API,1 req/s 限速,主备端点
        ▼
raw_page(全文 + 内链)                 --expand 可用内链发现新候选
        │  --extract    LLM 抽取 / 规则降级
        ▼
tech_candidate(EXTRACTED)+ tech_relation(依赖边)
        │  --export
        ▼
out/sparrow_new_nodes.sql   新节点+边,直接灌进 Sparrow 库
out/enrich_existing.sql     既有 77 节点的 detail 增补
out/rag_corpus.json         RAG 语料,给 Milvus embedding 流水线
```

## 快速开始

```bash
pip install -r requirements.txt

# 默认复用 Sparrow docker compose 里的 MySQL(宿主机 3307),无需额外数据库
# --all = 种子 → 爬取 → 抽取 → 直连同步进 Sparrow 业务库
python main.py --all --limit 20

# 分步执行
python main.py --init-seeds
python main.py --crawl --limit 20
python main.py --extract
python main.py --sync-sparrow     # 直连内化:节点/边/增补/RAG 语料 + Redis 缓存失效
python main.py --export           # (可选)导出 SQL/JSON 文件,用于离线传递
python main.py --status
```

## 与 Sparrow 的集成契约(--sync-sparrow)

直连写入 Sparrow 业务库(默认 `sparrow@127.0.0.1:3307`,见 config.py 的 `SPARROW_DB_*`):

1. `tech_node` / `tech_edge` — 新技术节点(id ≥ 1000,code `sp_*`)与依赖边,幂等 upsert
2. `tech_node.detail` — 既有节点增补,**仅当新内容比现有更长**才覆盖
3. `rag_document(code, name, title, url, content)` — 全量词条语料表(自动建表),
   Sparrow 后端的 embedding 管道按 `code` 对齐节点,把 `content` 切块进 Milvus
4. `DEL sparrow:graph:tree` — 失效 Redis 科技树缓存,前端立即可见新内容

## 配置(环境变量,见 config.py)

| 变量 | 默认 | 说明 |
|---|---|---|
| `SPIDER_DB_HOST/PORT/USER/PASSWORD` | 127.0.0.1:3307 root/root123 | 复用 Sparrow 的 MySQL 容器 |
| `SPIDER_WIKI_API` | 中文维基 api.php | 可指向任意 MediaWiki 站点/镜像 |
| `SPIDER_PROXY` | 空 | 大陆直连维基不可达时配置,如 `http://127.0.0.1:7890` |
| `SPIDER_INTERVAL` | 1.0 | 全局最小请求间隔(秒),请保持 ≥1 |
| `AI_BASE_URL` / `AI_API_KEY` / `AI_CHAT_MODEL` | 空 | OpenAI 兼容服务;未配置时规则降级 |

## 模式说明

- **LLM 模式**(配置 `AI_API_KEY`):抽取 era/年代/摘要/深度解读 + 依赖边;`--expand` 可用。
- **规则降级**:首段做摘要、前两段做 detail;不抽时代与边。降级产物**不进图谱**,
  但仍进 `rag_corpus.json` 和既有节点的 `enrich_existing.sql`(只在内容更充实时覆盖)。

## 导入 Sparrow

```bash
docker exec -i sparrow-mysql-1 mysql -usparrow -psparrow123 sparrow < out/sparrow_new_nodes.sql
docker exec -i sparrow-mysql-1 mysql -usparrow -psparrow123 sparrow < out/enrich_existing.sql
# 清掉科技树缓存让新节点立即可见
docker exec sparrow-redis-1 redis-cli del sparrow:graph:tree
```

新节点 id 从 1000 开始(`SPARROW_ID_BASE`),code 形如 `sp_<候选id>`,与内置 77 节点(id 1–77)隔离,
重复执行幂等(`ON DUPLICATE KEY UPDATE` / `INSERT IGNORE`)。

## 负责任的爬取

只调用 MediaWiki 官方 API(不抓 HTML 页面),亮明 User-Agent,串行 1 req/s 限速,
指数退避重试,主端点不可达自动切换备用端点。请勿将间隔调小于 1 秒。
