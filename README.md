<div align="center">

<h1>🐦 Sparrow · 科技树图谱 & 产业链深度调研系统</h1>

<p><strong>一个从 0 实现的微服务化知识图谱 + Multi-Agent 产业链调研平台</strong></p>

[![GitHub Stars](https://img.shields.io/github/stars/14120/Sparrow?style=flat-square)](https://github.com/)
[![GitHub Forks](https://img.shields.io/github/forks/14120/Sparrow?style=flat-square)](https://github.com/)
[![GitHub Issues](https://img.shields.io/github/issues/14120/Sparrow?style=flat-square)](https://github.com/)
[![License](https://img.shields.io/github/license/14120/Sparrow?style=flat-square)](./LICENSE)
[![Version](https://img.shields.io/badge/version-phase2-green.svg?style=flat-square)](#)
[![Java](https://img.shields.io/badge/Java-17-ED8B00?style=flat-square&logo=openjdk&logoColor=white)](https://openjdk.org/)
[![Spring Cloud](https://img.shields.io/badge/Spring%20Cloud-Alibaba-6DB33F?style=flat-square&logo=spring&logoColor=white)](https://sca.aliyun.com/)
[![Vue](https://img.shields.io/badge/Vue-3.5-42b883?style=flat-square&logo=vue.js&logoColor=white)](https://vuejs.org/)
[![Docker](https://img.shields.io/badge/Docker-Compose-2496ED?style=flat-square&logo=docker&logoColor=white)](https://hub.docker.com/)

线上预览地址: **https://dazhaxie75.top/** &ensp;|&ensp; 本地访问: **http://localhost:8080**

</div>

> [!IMPORTANT]
> 「**始于图谱，而不止于图谱。**」从一棵可视化的科技树出发，Sparrow 正在把「产业链」从一个静态展示页，演进成一个**会自己联网调研、带证据引用、可交互**的动态知识工件 —— 你只需像聊天一样提出想研究的产业，Multi-Agent 就会自主完成检索、交叉核验、图谱构建与深度报告撰写。

## ⚡ 项目概述

**Sparrow** 是一个以「知识图谱」为内核的微服务化平台，把**科技树可视化浏览**、**产业链 Multi-Agent 深度调研**、**AI 对话问答**与**会员支付**整合在一套从 0 搭建的 Spring Cloud Alibaba + Vue3 技术栈里。

> Sparrow 是一种体型娇小却敏捷的鸟 —— 它象征着「轻盈、专注、不惧复杂」。

系统不仅是一张可点击、按时代分层、自动高亮完整前置链的科技树，更在「产业链专题」里引入了 **Chain Research 深度调研引擎**：用户提出任意产业主题（如「中国人形机器人产业链」），与规划 Agent 对话收窄范围后，触发 **多个角色 Agent 并行反思 + 论坛主持人协作**的自动调研流程，最终交付带 `[S1]` 来源引用的**互动图谱 + 富格式深度报告**。

相比同类知识图谱项目，Sparrow 拥有 🚀 六大优势：

1. **微服务化与拆库治理**：6 个业务服务（gateway / user / graph / industry-chain / ai / trade）+ 5 个独立业务库，服务边界纪律严格：`gateway` 剥离伪造身份、下游只读 `X-User-Id`、服务间走 OpenFeign、支付回调走 Seata AT 全局事务。
2. **Neo4j 驱动的图读路径**：科技树全量读路径走 Neo4j，启动时从 MySQL 邻接表迁移并建立 `(:TechNode {id})` 唯一约束；社区簇布局预计算，千节点级别流畅交互。
3. **Multi-Agent 产业链调研**：对标业界多智能体系统的「论坛协作」机制 —— 行业 / 检索 / 洞察多类 Agent 并行反思循环，论坛主持人定期汇总观点整合与分歧，避免单一模型同质化。
4. **带证据引用的富报告**：调研结论不依赖 LLM 常识 —— 所有关系图节点 / 边、报告关键结论都强制 `[Sx]` 来源引用，并通过 Schema 校验 + 修复重试守住事实边界；前端以 Document IR 渲染交互式报告（目录、SWOT/PEST、图表、来源徽章）。
5. **三级 AI 降级与限流**：`/api/ai/ask` 在 Agent 可用时走 LangChain4j AiServices（图谱查询 / 向量检索 / 用户进度三类工具），失败降级到 Milvus RAG，再降级到规则匹配；Sentinel 限流保证核心浏览与支付链路不受影响。
6. **工程可观测与可验证**：从 Seata 全局事务回滚回归脚本、k6 压测拐点调优，到 SSE 流式调研进度、Redis token 鉴权与幂等支付回调，每个关键链路都有对应的验证脚本与文档。

<div align="center">

「**告别静态数据看板。在 Sparrow，一切由一个简单的问题开始，你只需像对话一样，提出你的研究需求。**」

</div>

## 🏗️ 系统架构

### 整体架构

| 服务 | 角色 | 职责 |
|------|------|------|
| **sparrow-gateway** | 唯一入口 | 静态前端托管、路由、Redis token 鉴权、`X-User-Id` 注入 |
| **sparrow-user** | 用户与会员 | 注册登录、会员状态、会员开通流水（审计/对账） |
| **sparrow-graph** | 科技树 | 节点 / 依赖关系 / 会员内容解锁，Neo4j 读路径 |
| **sparrow-industry-chain** | 产业链调研 | 用户调研卡片、对话收窄、Multi-Agent 调研、互动图谱、富报告 |
| **sparrow-ai** | 通用 AI | AI 问答（Agent/RAG/规则三级）、科技树 Agent、向量索引 |
| **sparrow-trade** | 商品与支付 | 商品、订单、沙箱支付回调（Seata AT 全局事务） |

**中间件**：MySQL 8（拆 5 业务库）/ Redis 7 / Nacos 2.4 / Kafka 3.7 / Neo4j 5 / Milvus 2.4（AI 层）/ Seata 2.1 / Sentinel。

### 服务化边界纪律

- 运行时 6 个业务服务；产业链调研是独立限界上下文，单列 `sparrow-industry-chain`。
- `gateway` 负责剥离外部伪造的 `X-User-Id`，根据 Redis token 重新注入身份；下游 MVC 服务只读取 `X-User-Id`，不再各自查 Redis token。
- 服务间调用使用 OpenFeign，例如 `trade -> user` 开通会员、`ai -> graph/user` 查询上下文、`industry-chain -> user` 查询会员配额。
- 数据默认拆为 `sparrow_user / sparrow_graph / sparrow_trade / sparrow_ai / sparrow_industry_chain` 五个业务库。
- 支付回调的 `trade` 标记支付 + `user` 开通会员使用 Seata AT 全局事务配置。
- `trade` 在事务提交后发布 `OrderPaidEvent`；`graph` 可通过内部 `/internal/graph/reindex` 发布 `GraphChangedEvent`；`ai` 监听图谱变更后登记 `kafka_consumed_event` 幂等记录并异步触发 RAG 索引同步。
- `user` 消费 `OrderPaidEvent` 写 `member_grant_log` 会员开通流水（审计/对账），`order_no` 唯一键天然幂等；会员开通本身仍由 trade 全局事务完成，事件消费不重复开通。
- `ai`/`user` 消费者使用 `ErrorHandlingDeserializer` + `DefaultErrorHandler`，坏消息只 log+skip，不阻塞 consumer group。
- `graph` 的科技树读路径全部走 Neo4j，启动时 `Neo4jMigrator` 从 MySQL 邻接表同步并建立 `(:TechNode {id})` 唯一约束及 `era_rank/code` 索引。
- `ai` 的 `/api/ai/ask` 在 LLM/Agent 可用时走 LangChain4j AiServices（`GraphQueryTool`/`VectorSearchTool`/`UserProgressTool`），并经 Sentinel `@SentinelResource` 限流（默认 5 QPS，触发返回 429）。
- `industry-chain` 统一暴露 `/api/chains/cards/**`；旧静态产业链 `/api/chains/{slug}/graph` 与旧 AI 子路径 `/api/ai/chain-research/**` 不再作为正式入口。
- `sparrow` 保留为爬虫语料 staging schema，供 AI 读取 `rag_document`。

### 产业链 Multi-Agent 调研流程

对标业界多智能体系统的「论坛协作」机制，一次完整调研流程：

| 步骤 | 阶段 | 主要操作 | 参与组件 | 循环特性 |
|------|------|----------|----------|----------|
| 1 | 对话收窄 | 用户与规划 Agent 多轮对话明确范围 | 工作台 / Planner Agent | - |
| 2 | 并行启动 | 多个角色 Agent 同时开始首轮概览检索 | 行业 / 检索 / 洞察 Agent | - |
| 3 | 首轮总结 | 各 Agent 概览搜索 → 段落总结（写入论坛） | Agent + 论坛总线 | - |
| 4-N | **反思循环** | **论坛协作 + 深度检索** | **ForumBus + 主持人 + 全部 Agent** | **每 Agent 多轮** |
| 4.1 | 深度检索 | 各 Agent 基于缺口分析与主持人引导做专项检索 | Agent + 反思机制 | 每轮 |
| 4.2 | 主持人汇总 | 攒够一批 Agent 发言后触发主持人四段式总结 | Host LLM | 周期触发 |
| 4.3 | 软耦合调整 | 各 Agent 反思总结前读取最新主持人引导注入 prompt | Agent + ForumBus | 每轮 |
| N+1 | 证据核验 | 证据 Agent 整理可被来源直接支持的事实，标注 `[Sx]` | 证据 Agent | - |
| N+2 | 图谱构建 | 仅据核验证据生成带来源引用的 JSON 关系图（Schema 校验 + 修复重试） | 图谱 Agent | - |
| N+3 | 报告生成 | 基于 IR 渲染交互式富报告（目录 / SWOT / PEST / 图表 / 来源附录） | 报告 Agent | - |

## 📁 项目代码结构树

```
Sparrow/
├── backend/                               # 后端:Spring Cloud Alibaba 微服务
│   ├── sparrow-common/                    # 共享契约:响应、异常、安全上下文、事件常量
│   ├── sparrow-gateway/                   # 唯一入口:静态前端、路由、Redis token 鉴权
│   ├── sparrow-user/                      # 用户、登录、会员状态、开通流水
│   ├── sparrow-graph/                     # 科技树节点、依赖关系、会员内容解锁(Neo4j 读路径)
│   ├── sparrow-industry-chain/            # 产业链调研限界上下文(独立 sparrow_industry_chain 库)
│   │   └── .../industrychain/             # card / run / workflow / source / forum / graph / report
│   ├── sparrow-ai/                        # 通用 AI 问答、RAG、科技树 Agent、向量索引
│   ├── sparrow-trade/                     # 商品、订单、支付、沙箱回调(Seata AT 全局事务)
│   ├── docker/                            # 中间件配置:mysql my.cnf / redis.conf / 初始化脚本
│   ├── scripts/                           # 运维与验证脚本
│   │   ├── mvn17.ps1                      # Java 21 构建/测试入口(保留旧文件名)
│   │   ├── smoke.ps1                      # 端到端冒烟(注册/浏览/AI/下单/回调幂等)
│   │   ├── load.ps1 / k6-load.ps1         # k6 压测
│   │   ├── phase2-rollback-check.ps1      # Seata AT 全局事务回滚回归
│   │   └── migrate-*.sql                  # 各阶段数据迁移脚本
│   ├── Dockerfile                         # 多服务多阶段构建(各 service 有独立 runtime target)
│   └── pom.xml
├── frontend/                              # 前端:Vue 3.5 + Vite + Pinia
│   └── src/modules/
│       ├── graph/                         # 科技树图谱(Sigma + Graphology,沉浸全屏/社区簇/对比)
│       ├── industry-chain/                # 产业链调研首页 + 工作台
│       │   ├── views/                     # IndustryChainHomeView / IndustryChainWorkbenchView
│       │   └── components/                # ResearchGraph / RichReport / AgentForum
│       ├── ai/                            # AI 对话(右侧抽屉、流式问答、RAG、Markdown 渲染)
│       ├── trade/                         # 会员支付(沙箱收银台)
│       └── user/                          # 登录 / 注册
├── tools/
│   ├── sparrow-spider/                    # 一次性爬虫管线:维基词条抓取 → 抽取 → 导入 graph/RAG
│   └── sparrow-layout/                    # 一次性 LOD 布局预计算:tech_node/edge → node_layout
├── docs/                                  # 设计文档:作战手册、架构演进、代码规范、各阶段 PRD
├── chain-research-prd/                    # 产业链深度调研模块 PRD(HTML)
├── docker-compose.yml                     # Phase 2 多服务编排
├── .env.example                           # 环境变量示例
└── README.md
```

## 🚀 快速开始（Docker）

AI 层（含 Milvus / Sentinel / sparrow-ai）是项目核心，默认随核心栈一起启动：

```bash
docker compose up -d --build
```

> **16G 及以下机器**请先用 `docker-compose.local.yml`（已 gitignore，仅本地用）叠加降配，否则全栈可能 OOM：
> ```bash
> docker compose -f docker-compose.yml -f docker-compose.local.yml up -d --build
> ```

**首次启动 / 从旧版升级**：MySQL 的 init SQL 只在首次建卷执行，存量卷需手动跑迁移脚本补齐新库（否则 `sparrow-industry-chain` 会因 `sparrow_industry_chain` 库缺失而崩溃）：
```bash
MYSQL_CID=$(docker compose ps -q mysql)
for sql in backend/scripts/migrate-*.sql; do
  docker cp "$sql" "${MYSQL_CID}:/tmp/migrate.sql"
  docker exec "$MYSQL_CID" sh -c "mysql -uroot -proot123 --default-character-set=utf8mb4 < /tmp/migrate.sql"
done
```

需要全新验证拆库结构时，先确认可以清空本地数据，再执行 `docker compose down -v`。

全部就绪后访问 **http://localhost:8080**。线上预览地址：**https://dazhaxie75.top/**。

> 📖 **完整的本地部署流程（WSL2 内存配置、国内镜像加速器、降配方案、故障排查表）见 [docs/本地部署手册.md](docs/本地部署手册.md)。**

## 🔧 源码启动指南

### 环境要求

- **操作系统**: Windows / Linux / MacOS
- **JDK**: 21
- **Node.js**: 20+（前端构建）
- **数据库**: MySQL 8（推荐，已按拆库结构初始化）
- **中间件**: Redis / Nacos / Kafka / Neo4j（AI 层还需 Milvus）

### 1. 启动中间件与后端

通过 `docker-compose.yml` 拉起中间件，再用 Maven 构建并启动各业务服务。Java 21 构建入口（脚本为兼容保留旧文件名）：

```powershell
powershell -ExecutionPolicy Bypass -File backend/scripts/mvn17.ps1 test
```

各业务服务为独立 Spring Boot 应用，可分别启动（gateway 默认监听 8080，对外暴露前端与统一 API）。

### 2. 启动前端

```bash
cd frontend
npm install
npm run dev      # 开发模式;构建用 npm run build
```

### 3. 配置 LLM 与 AI（可选，启用 AI 对话与产业链调研）

在项目根目录建 `.env`（任意 OpenAI 兼容服务均可）：

```env
AI_BASE_URL=https://open.bigmodel.cn/api/paas/v4
AI_API_KEY=your_key
AI_CHAT_MODEL=glm-4.5-air
AI_EMBEDDING_MODEL=embedding-3
```

未配置时，AI 对话走规则降级，产业链调研无法启动联网研究 —— 核心图谱浏览、调研卡片管理与支付链路不受影响。

## ⚙️ 配置说明

### 中间件配置

| 中间件 | 默认端口 | 说明 |
|:---|:---|:---|
| MySQL | 3307→3306 | 拆 5 业务库：`sparrow_user / sparrow_graph / sparrow_trade / sparrow_ai / sparrow_industry_chain` |
| Redis | 6379 | token 鉴权、配额计数、图谱读缓存 |
| Nacos | 8848 / 9848 | 服务注册与配置（2.x gRPC 走 9848） |
| Kafka | 9094→9092 | 事件总线：`OrderPaidEvent` / `GraphChangedEvent` |
| Neo4j | 7474 / 7687 | 科技树读路径（账号 `neo4j / sparrow2024`） |
| Milvus | 19530 | AI 层向量库（`--profile ai`） |

### AI 模式说明

| 模式 | 条件 | 行为 |
|---|---|---|
| **Agent 模式** | 配置 `AI_API_KEY` + ChatModel 可用 | LangChain4j Agent 调用图谱查询、向量检索、用户进度三类工具，支持多轮记忆 |
| **RAG 模式** | Agent 调用失败 | 问题向量化 → Milvus 检索 top-5 节点 → LLM 生成回答 |
| **规则模式** | 未配置 `AI_API_KEY` 或 LLM 调用失败 | 走关键词匹配 + 图谱前置链拼装回答，不依赖外部模型 |
| **限流命中** | 默认 5 QPS / `SENTINEL_AI_ASK_QPS` | 返回业务 429「请求过于频繁」，核心浏览与支付链路不受影响 |

## ✨ 功能

| 功能 | 入口 | 说明 |
|---|---|---|
| 科技树图谱 | 首页 | 按时代分层布局，点击节点看详情，自动高亮完整前置技术链；支持社区簇模式与对比 |
| **产业链调研工作台** | `/chains`（登录后） | Multi-Agent 论坛协作调研：对话收窄 → 多 Agent 并行反思 → 主持人汇总 → 带引用的互动图谱 + 富报告 |
| AI 向导 | 右下角抽屉 | 登录后提问；Agent/RAG/规则三级降级；免费用户每日 3 次 |
| 会员支付 | 右上角 | 下单 → 沙箱收银台 → 模拟支付回调 → 会员开通（幂等 + Seata 全局事务） |

## 📡 API 速览

```text
POST /api/user/register        注册 {username,password} -> {token}
POST /api/user/login           登录 -> {token}
GET  /api/user/me              当前用户(需 Bearer token)
GET  /api/graph/tree           全树(Redis 缓存)
GET  /api/graph/node/{id}      节点详情(含前置/解锁;会员内容鉴权)
GET  /api/graph/node/{id}/prerequisites  完整前置链(反向 BFS)
GET  /api/chains/cards          我的调研卡片列表(登录)
POST /api/chains/cards          新建调研卡片 {title,brief,sources?}
GET  /api/chains/cards/{id}     卡片详情(图谱/报告 IR/来源/论坛)
PUT  /api/chains/cards/{id}     更新调研卡片
DELETE /api/chains/cards/{id}   删除调研卡片
POST /api/chains/cards/{id}/messages       追加对话消息
POST /api/chains/cards/{id}/runs           启动 Multi-Agent 调研
GET  /api/chains/cards/{id}/events         SSE 实时进度与论坛流
POST /api/trade/order          下单 {productCode} -> {orderNo,payUrl}
POST /api/pay/mock/notify      模拟支付回调 {orderNo,payToken}(HMAC 校验 + 幂等)
POST /api/ai/ask               AI 问答 {question}(需登录)
```

统一响应：`{code:0, message:"ok", data:...}`，业务错误 code 非 0（401 未登录 / 429 限额 / 404 不存在）。

## 🧪 本地验证

```powershell
# Java 21 构建/测试
powershell -ExecutionPolicy Bypass -File backend/scripts/mvn17.ps1 test
docker build -f backend/Dockerfile --target gateway-runtime -t sparrow-gateway:phase2-check .

# 架构边界守卫(防止旧产业链模块/入口被重新接回)
cd frontend
npm run guard:architecture
cd ..

# 端到端冒烟(注册登录、科技树浏览、AI、会员下单、Mock 支付回调校验、重复回调幂等、会员解锁和订单列表)
powershell -File backend/scripts/smoke.ps1

# 压测
powershell -File backend/scripts/load.ps1 -Qps 50 -DurationSeconds 30 -P99Ms 50

# Seata AT 全局事务回滚回归(自动拉起独立 compose、注入失败、断言状态、收尾 down -v)
powershell -ExecutionPolicy Bypass -File backend/scripts/phase2-rollback-check.ps1
```

若未配置模型，AI 会走规则降级或部分模型能力不可用。

## 🤖 Agent Harness

仓库内置了面向编码 Agent 的控制层：项目地图、服务边界守卫、按改动范围执行的验证、长任务检查点和失败恢复手册。入口是 [AGENTS.md](AGENTS.md)，详细说明位于 [docs/harness/](docs/harness/)：

```bash
node tools/harness.mjs doctor               # 检查本机工具链
node tools/harness.mjs guard                # 秒级确定性架构/安全规则
node tools/harness.mjs changed              # 仅验证当前改动影响的模块
node tools/harness.mjs full                 # 全量后端测试 + 前端构建
node tools/harness.mjs task:init <id> <标题> # 创建可跨会话续接的任务状态
```

当 Agent、评审或线上故障暴露出重复问题时，应优先把反馈沉淀为测试、守卫规则、诊断命令或 `docs/harness/failure-playbook.md` 条目，而不是只增加一段提示词。

## 🤝 贡献指南

我们欢迎所有形式的贡献！请先阅读 `docs/代码规范.md` 与 `docs/作战手册.md`，了解服务化边界纪律与提交规范后再提 PR。

## ⚠️ 免责声明

**重要提醒：本项目仅供学习、学术研究和教育目的使用**

1. **合规性声明**：本项目所有代码、工具和功能均仅供学习、学术研究和教育目的使用；严禁用于任何商业用途或盈利性活动；严禁用于任何违法、违规或侵犯他人权益的行为。
2. **数据来源声明**：产业链与科技树数据来源于公开维基词条的 LLM 抽取，属于粗略草图，非权威商业数据库；AI 调研结论均标注来源引用，但仍可能存在偏差，使用者应自行核验。
3. **AI 内容免责**：AI 问答与 Multi-Agent 调研结果由大语言模型生成，可能存在事实错误或幻觉；使用者应结合来源引用独立判断，不得将分析结果用于商业决策或盈利目的。
4. **技术免责**：本项目按「现状」提供，不提供任何明示或暗示的保证；作者不对使用本项目造成的任何直接或间接损失承担责任。
5. **责任限制**：使用者在使用本项目前应充分了解相关法律法规；因违反法律法规使用本项目而产生的任何后果由使用者自行承担。

**请在使用本项目前仔细阅读并理解上述免责声明。使用本项目即表示你已同意并接受上述所有条款。**

## 📄 许可证

本项目采用开源许可证，详细信息请参阅 [LICENSE](./LICENSE) 文件。

## 📈 项目统计

<a href="https://www.star-history.com/#14120/Sparrow&type=date&legend=top-left">
 <picture>
   <source media="(prefers-color-scheme: dark)" srcset="https://api.star-history.com/svg?repos=14120/Sparrow&type=date&theme=dark&legend=top-left" />
   <source media="(prefers-color-scheme: light)" srcset="https://api.star-history.com/svg?repos=14120/Sparrow&type=date&legend=top-left" />
   <img alt="Star History Chart" src="https://api.star-history.com/svg?repos=14120/Sparrow&type=date&legend=top-left" />
 </picture>
</a>
