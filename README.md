# Sparrow · 人类科技树(Phase 2 M5)

从 50 万年前的「火」到未来的 AGI:77 项关键技术、110+ 条依赖边的可视化科技树,
附带 AI 向导(RAG/Agent)与会员支付闭环。

当前形态是 **Phase 2 M2~M5 主干已落地**:Spring Cloud Gateway + Nacos + OpenFeign,
拆成 5 个运行时服务 `gateway / user / graph / ai / trade`,完成四个业务库拆分与
trade/user 支付链路 Seata AT 运行态回滚演练,接入并验证 Kafka 投递/消费与消费幂等。
M4 graph 主读路径已切 Neo4j(MySQL `tech_node/tech_edge` 退化为种子写入源),
M5 接入 LangChain4j Agent 工具调用与 Sentinel 兜底限流(Dashboard 待后续接入)。

前端入口和 API 契约保持不变:宿主机只暴露 `http://localhost:8080`,
前端仍请求 `/api/**`。

## 一键启动

默认启动核心链路(gateway/user/graph/trade + MySQL/Redis/Nacos):

```bash
docker compose up -d --build
```

如果本机已经有 Phase 1 的 `mysql_data` 卷,初始化脚本不会重新执行。需要全新验证拆库结构时,
先确认可以清空本地数据,再执行 `docker compose down -v`。

需要 AI/RAG 和 Milvus 时启用 `ai` profile:

```bash
docker compose --profile ai up -d --build
```

全部就绪后访问 **http://localhost:8080**。

## 功能

| 功能 | 入口 | 说明 |
|---|---|---|
| 科技树图谱 | 首页 | 按时代分层布局,点击节点看详情,自动高亮完整前置技术链 |
| AI 向导 | 右下角 | `ai` profile 启动后可用;登录后提问,免费用户每日 3 次 |
| 会员支付 | 右上角 | 下单 -> 沙箱收银台 -> 模拟支付回调 -> 会员开通(幂等) |

## Phase 2 架构

```text
backend
├── sparrow-common   # 共享契约:响应、异常、安全上下文、事件常量
├── sparrow-gateway  # 唯一入口:静态前端、路由、Redis token 鉴权、X-User-Id 注入
├── sparrow-user     # 用户、登录、会员状态
├── sparrow-graph    # 科技树节点、依赖关系、会员内容解锁
├── sparrow-ai       # AI 问答、RAG、向量索引;默认通过 ai profile 启动
└── sparrow-trade    # 商品、订单、支付、支付回调
```

服务化边界纪律:

- 运行时只保留 5 个服务,不新增 `notify` 等第 6 个服务。
- `gateway` 负责剥离外部伪造的 `X-User-Id`,根据 Redis token 重新注入身份。
- 下游 MVC 服务只读取 `X-User-Id`,不再各自查 Redis token。
- 服务间调用使用 OpenFeign,例如 `trade -> user` 开通会员、`ai -> graph/user` 查询上下文。
- 数据默认拆为 `sparrow_user / sparrow_graph / sparrow_trade / sparrow_ai` 四个业务库。
- 支付回调的 `trade` 标记支付 + `user` 开通会员使用 Seata AT 全局事务配置。
- `trade` 在事务提交后发布 `OrderPaidEvent`;`graph` 可通过内部 `/internal/graph/reindex` 发布 `GraphChangedEvent`;`ai` 监听图谱变更后登记 `kafka_consumed_event` 幂等记录并异步触发 RAG 索引同步。
- `user` 消费 `OrderPaidEvent` 写 `member_grant_log` 会员开通流水(审计/对账),`order_no` 唯一键天然幂等;会员开通本身仍由 trade 全局事务完成,事件消费不重复开通。
- `ai`/`user` 消费者使用 `ErrorHandlingDeserializer` + `DefaultErrorHandler`,坏消息只 log+skip,不阻塞 consumer group。
- `graph` 的科技树读路径全部走 Neo4j,启动时 `Neo4jMigrator` 从 MySQL 邻接表同步并建立 `(:TechNode {id})` 唯一约束及 `era_rank/code` 索引。
- `ai` 的 `/api/ai/ask` 在 LLM/Agent 可用时走 LangChain4j AiServices(`GraphQueryTool`/`VectorSearchTool`/`UserProgressTool`),并经 Sentinel `@SentinelResource` 限流(默认 5 QPS,触发返回 429)。
- `sparrow` 保留为爬虫语料 staging schema,供 AI 读取 `rag_document`。

## API 速览

```text
POST /api/user/register        注册 {username,password} -> {token}
POST /api/user/login           登录 -> {token}
GET  /api/user/me              当前用户(需 Bearer token)
GET  /api/graph/tree           全树(Redis 缓存)
GET  /api/graph/node/{id}      节点详情(含前置/解锁;会员内容鉴权)
GET  /api/graph/node/{id}/prerequisites  完整前置链(反向 BFS)
GET  /api/trade/products       商品目录
POST /api/trade/order          下单 {productCode} -> {orderNo,payUrl}
POST /api/pay/mock/notify      模拟支付回调 {orderNo,payToken}(HMAC 校验 + 幂等)
POST /api/ai/ask               AI 问答 {question}(需登录;需要 ai profile)
```

统一响应:`{code:0, message:"ok", data:...}`,业务错误 code 非 0(401 未登录 / 429 限额 / 404 不存在)。

## AI 模式说明

| 模式 | 条件 | 行为 |
|---|---|---|
| Agent 模式 | 启动 `ai` profile + 配置 `AI_API_KEY` + ChatModel 可用 | LangChain4j Agent 调用图谱查询、向量检索、用户进度三类工具,支持多轮记忆 |
| RAG 模式 | Agent 调用失败 | 问题向量化 -> Milvus 检索 top-5 节点 -> LLM 生成回答 |
| 规则模式 | 未配置 `AI_API_KEY` 或 LLM 调用失败 | 走关键词匹配 + 图谱前置链拼装回答,不依赖外部模型 |
| 限流命中 | 默认 5 QPS / `SENTINEL_AI_ASK_QPS` | 返回业务 429「请求过于频繁」,核心浏览和支付链路不受影响 |

启用 RAG:在项目根目录建 `.env`(任意 OpenAI 兼容服务均可):

```env
AI_BASE_URL=https://dashscope.aliyuncs.com/compatible-mode/v1
AI_API_KEY=sk-xxx
AI_CHAT_MODEL=qwen-plus
AI_EMBEDDING_MODEL=text-embedding-v3
```

## 本地验证

本机 Java 仍可能是 Java 8,后端验证统一使用 Docker 内的 Java 17:

```powershell
powershell -ExecutionPolicy Bypass -File backend/scripts/mvn17.ps1 test
docker build -f backend/Dockerfile --target gateway-runtime -t sparrow-gateway:phase2-check .
```

服务启动后执行冒烟:

```powershell
powershell -File backend/scripts/smoke.ps1
powershell -File backend/scripts/load.ps1 -Qps 50 -DurationSeconds 30 -P99Ms 200
```

M2 Seata AT 全局事务回滚回归(自动拉起独立 `sparrow_phase2_check` compose、注入 `TRADE_FAIL_AFTER_MEMBERSHIP_GRANT=true`、断言数据库状态、收尾 `down -v`):

```powershell
powershell -ExecutionPolicy Bypass -File backend/scripts/phase2-rollback-check.ps1
```

`smoke.ps1` 验证注册登录、科技树浏览、AI、会员下单、Mock 支付回调校验、重复回调幂等、会员解锁和订单列表。
若未启动 `ai` profile 或未配置模型,AI 相关步骤会失败;这是 M1 的分组启动策略导致的预期差异。

## 后续里程碑

- M2:✅ 拆分业务库 + Seata AT 已落地,回滚演练已脚本化(`backend/scripts/phase2-rollback-check.ps1`)。
- M3:✅ Kafka 事件骨架已落地,坏消息容错(ErrorHandlingDeserializer + DefaultErrorHandler)已接入。
- M4:✅ graph 主读路径已切 Neo4j,启动期自动迁移 + 建唯一约束,`GraphServiceTest` 已覆盖读路径;待办为多跳 Cypher 的运行态/集成验证。
- M5:✅ LangChain4j Agent(LRU 会话记忆)+ Sentinel 限流(Nacos 持久化数据源 + `sentinel-dashboard`)已接入;待办为运行态把 `deploy/sentinel/sparrow-ai-flow-rules.json` 导入 Nacos、Agent 端到端集成测试。

更多状态见 [docs/阶段二服务化实施状态.md](docs/阶段二服务化实施状态.md) 和 [ROADMAP.md](ROADMAP.md)。
