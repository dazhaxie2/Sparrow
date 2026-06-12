# Sparrow · 人类科技树(Phase 1)

从 50 万年前的「火」到未来的 AGI:77 项关键技术、110+ 条依赖边的可视化科技树,
附带 AI 向导(RAG)与会员支付闭环。

Phase 1 形态:**Spring Boot 3 模块化单体**(`user / graph / ai / trade` 四模块)+ MySQL + Redis + Milvus,
Docker Compose 一键起。演进路线见 [ROADMAP.md](ROADMAP.md)。

## 一键启动

```bash
docker compose up -d --build
```

首次启动会构建应用镜像(Maven 走阿里云镜像)并拉取 MySQL/Redis/Milvus 镜像。
全部就绪后访问 **http://localhost:8080**。

## 功能

| 功能 | 入口 | 说明 |
|---|---|---|
| 科技树图谱 | 首页 | 按时代分层布局,点击节点看详情,自动高亮完整前置技术链 |
| AI 向导 | 右下角 | 登录后提问;免费用户每日 3 次,会员不限次 |
| 会员支付 | 右上角 | 下单 → 沙箱收银台 → 模拟支付回调 → 会员开通(幂等) |

👑 标记的节点详情为会员专属深度内容。

## AI 模式说明

| 模式 | 条件 | 行为 |
|---|---|---|
| RAG 模式 | 配置了 `AI_API_KEY` | 问题向量化 → Milvus 检索 top-5 节点 → LLM 生成回答 |
| 规则模式 | 未配置(默认) | 节点名关键词匹配 → 图谱遍历生成确定性回答(前置链/解锁) |

启用 RAG:在项目根目录建 `.env`(任意 OpenAI 兼容服务均可):

```env
AI_BASE_URL=https://dashscope.aliyuncs.com/compatible-mode/v1
AI_API_KEY=sk-xxx
AI_CHAT_MODEL=qwen-plus
AI_EMBEDDING_MODEL=text-embedding-v3
```

应用启动后会在后台把 77 个节点 embedding 写入 Milvus(Milvus 慢启动会自动重试,不阻塞应用)。

## API 速览

```
POST /api/user/register        注册 {username,password} → {token}
POST /api/user/login           登录 → {token}
GET  /api/user/me              当前用户(需 Bearer token)
GET  /api/graph/tree           全树(Redis 缓存)
GET  /api/graph/node/{id}      节点详情(含前置/解锁;会员内容鉴权)
GET  /api/graph/node/{id}/prerequisites  完整前置链(反向 BFS)
GET  /api/trade/products       商品目录
POST /api/trade/order          下单 {productCode} → {orderNo,payUrl}
POST /api/pay/mock/notify      模拟支付回调 {orderNo,payToken}(HMAC 校验 + 幂等)
POST /api/ai/ask               AI 问答 {question}(需登录,免费每日 3 次)
```

统一响应:`{code:0, message:"ok", data:...}`,业务错误 code 非 0(401 未登录 / 429 限额 / 404 不存在)。

## Phase 1 的架构纪律

- 单库本地事务(下单核销 + 开通会员同一事务)——**没有引入 Seata**,跨服务事务是 Phase 2 拆分后的事
- 模块间只通过接口依赖(如 `MembershipService`),为 Phase 2 拆微服务预埋缝
- 科技树全量进 Redis(读多写少小数据),树接口命中缓存后不打 MySQL
- Milvus / LLM 全部优雅降级:挂了不影响核心浏览与支付链路

后端采用轻量 DDD 的模块化单体包结构:

```text
com.sparrow
├── common                         # 横切能力:响应、异常、安全、Web 配置
├── user                           # 用户与会员上下文
│   ├── api                        # 对其他模块暴露的边界接口
│   ├── application                # 应用服务/用例编排
│   ├── domain                     # 领域模型
│   ├── infrastructure             # Mapper 等基础设施
│   └── interfaces                 # HTTP 入口
├── trade                          # 商品、订单、支付上下文
├── graph                          # 科技树图谱上下文
└── ai                             # AI 问答与 RAG 上下文
```

阶段一先保持单进程部署,不拆微服务;后续拆分时优先沿 `user / trade / graph / ai`
这些限界上下文演进。

前端采用按功能分片的模块化结构,不照搬后端 DDD 分层:

```text
frontend/src
├── app                  # Vue 入口、路由、应用级组件
├── shared               # request、通用类型、工具、全局样式
└── modules
    ├── user             # 登录、用户状态、会员状态
    ├── trade            # 商品、下单、支付页
    ├── graph            # 科技树图谱、节点详情
    └── ai               # AI Dock、问答 API、会话状态
```

模块内优先自包含 `api / components / views / store / types`;跨模块访问应优先走
对方模块暴露的 API/store/types,通用能力再下沉到 `shared`。

## Phase 1 验收

启动后执行:

```powershell
powershell -File scripts/smoke.ps1
powershell -File scripts/load.ps1 -Qps 50 -DurationSeconds 30 -P99Ms 200
```

`smoke.ps1` 验证注册登录、科技树浏览、AI 规则降级、会员下单、Mock 支付回调校验、重复回调幂等、会员解锁和订单列表。
`load.ps1` 默认对 `/api/graph/tree` 做 50 QPS / 30 秒压测,要求 P99 <= 200ms。

若本机 `node` 不在 PATH,可显式传入 Node 路径:

```powershell
powershell -File scripts/load.ps1 -Node "C:\path\to\node.exe"
```
