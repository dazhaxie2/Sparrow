# Sparrow · 人类科技树(Phase 2 M3)

从 50 万年前的「火」到未来的 AGI:77 项关键技术、110+ 条依赖边的可视化科技树,
附带 AI 向导(RAG)与会员支付闭环。

当前形态是 **Phase 2 M3 基线**:Spring Cloud Gateway + Nacos + OpenFeign,
拆成 5 个运行时服务 `gateway / user / graph / ai / trade`,并完成四个业务库拆分与
trade/user 支付链路 Seata AT 配置,同时接入 Kafka 事件骨架。Neo4j、Agent、Sentinel 在 M4-M5 继续推进。

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

## Phase 2 M3 架构

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
- `trade` 在事务提交后发布 `OrderPaidEvent`;`graph` 可通过内部 `/internal/graph/reindex` 发布 `GraphChangedEvent`;`ai` 监听图谱变更后触发 RAG 索引同步。
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
| RAG 模式 | 启动 `ai` profile 且配置了 `AI_API_KEY` | 问题向量化 -> Milvus 检索 top-5 节点 -> LLM 生成回答 |
| 未配置模型 | 未配置 `AI_API_KEY` | `/api/ai/ask` 返回 503,核心浏览和支付链路不受影响 |

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

`smoke.ps1` 验证注册登录、科技树浏览、AI、会员下单、Mock 支付回调校验、重复回调幂等、会员解锁和订单列表。
若未启动 `ai` profile 或未配置模型,AI 相关步骤会失败;这是 M1 的分组启动策略导致的预期差异。

## 后续里程碑

- M2:拆分业务库 + Seata AT 已落地,仍需补运行态支付回滚演练。
- M3:Kafka 事件骨架已落地,仍需补运行态投递/消费验证和消费幂等存储。
- M4:图谱从 MySQL 邻接表迁移到 Neo4j。
- M5:LangChain4j Agent 工具调用 + Sentinel 限流熔断。

更多状态见 [docs/阶段二服务化实施状态.md](docs/阶段二服务化实施状态.md) 和 [ROADMAP.md](ROADMAP.md)。
