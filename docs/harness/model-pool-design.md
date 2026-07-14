# 跨服务全局模型池设计与落地记录

状态：本地部署迁移、六场景激活、remote/fallback/recovery 已验证；真实 chat、SSE、规划已通过，
完整产业链与 embedding 尚未收口（2026-07-14）。

本文记录 Sparrow 的模型配置管理面：激活配置统一保存在
`sparrow_industry_chain.model_config`，各固定场景独立选择模型；配置所有权、
密钥加密和审计仍由 `sparrow-industry-chain` 负责，消费服务只通过受保护的
内部契约读取自身场景。本文同时是后续新增场景、排障和回退的权威说明。

## 目标与非目标

目标：

1. 一个管理页查看和维护两个 AI 服务的固定模型场景。
2. 每个场景最多激活一条配置，chat 与 embedding 不得混用。
3. 产业链四个场景保留事务提交后的内存热切换。
4. `sparrow-ai` 启动时从权威模型池装配 chat、streaming chat 和 embedding，
   内部服务不可用时有边界明确的环境配置兜底。
5. API Key 始终加密落库、管理端脱敏、内部传输鉴权且不落日志。

非目标：

- 不新增独立 model-config 服务，不共享数据库账号，不引入多数据源或分布式事务。
- 不把 LangChain4j 下沉到 `sparrow-common`。
- 本版不让 `sparrow-ai` 运行中自动热刷新；其模型池变更在下次启动时生效。
- 本版不改变公开 AI、RAG 或产业链 API。

## 所有权与运行拓扑

```mermaid
flowchart LR
    Admin["管理员模型配置页"] -->|"/api/chains/admin/model-configs/**"| Gateway["sparrow-gateway"]
    Gateway --> Owner["sparrow-industry-chain"]
    Owner --> DB[("sparrow_industry_chain.model_config")]
    Owner -->|"afterCommit swap"| ChainPool["产业链内存模型池"]
    AI["sparrow-ai"] -->|"Feign /internal/chains/model-configs/active"]| Owner
    AI -->|"启动期装配"| AiBeans["chat / streaming / embedding Beans"]
    Env["服务环境配置"] -. "远端不可用时兜底" .-> AI
```

关键边界：

- `sparrow-industry-chain` 是表、密钥加解密、管理员写入、激活和审计的唯一所有者。
- `sparrow-ai` 只读取 `sparrow_ai_chat`、`sparrow_ai_embedding`，不读取产业链场景，
  也不连接 `sparrow_industry_chain` 数据库。
- `sparrow-common` 只包含稳定数据契约和校验规则；业务服务仍独立构建模型对象。

## 固定场景

`ModelScene` 是代码拥有的枚举，不接受自由文本。每个场景在枚举中声明唯一允许的
`ModelKind`，保存、激活和跨服务读取都会重复校验。

| scene | 中文名 | kind | 主要消费方 | 激活生效时间 |
| --- | --- | --- | --- | --- |
| `sparrow_ai_chat` | 科技图 AI 对话 | `chat` | `AiService`、`TechTreeAgent` | `sparrow-ai` 下次启动 |
| `sparrow_ai_embedding` | 科技图向量检索 | `embedding` | `AiService.embed`、RAG 索引 | `sparrow-ai` 下次启动 |
| `chain_planning` | 调研规划与问答 | `chat` | 调研规划、证据核验、问答、论坛总结 | 事务提交后立即热切换 |
| `chain_extraction` | 图谱抽取 | `chat` | `ResearchGraphExtractor` | 事务提交后立即热切换 |
| `chain_report` | 报告生成 | `chat` | `ResearchReportBuilder` | 事务提交后立即热切换 |
| `chain_agent_stream` | Agent 流式总结 | `chat` | `IndustryChainResearchAgent` | 事务提交后立即热切换 |

新增场景必须同时更新：

- `ModelScene` 及其 `expectedKind`；
- 实际消费方映射和聚焦测试；
- 前端 `MODEL_SCENES`；
- 本文和需要的迁移/部署配置。

## 数据模型与迁移

权威表仍为 `sparrow_industry_chain.model_config`。新增字段：

```sql
scene       VARCHAR(48) NOT NULL DEFAULT 'chain_planning'
model_kind  VARCHAR(16) NOT NULL DEFAULT 'chat'
```

兼容策略：

- `IndustryChainRepository.ensureTables()` 创建新表时直接包含两列，老库启动时幂等补列。
- `backend/scripts/migrate-model-config-scene.sql` 可由运维重复执行；已有记录保留原 active
  状态，缺失值归入 `chain_planning` + `chat`。
- 迁移仅增加字段，不删除列、不改密文、不清理历史配置。

每场景最多一条 active 由应用事务保证，不建立 `(scene, active)` 唯一索引，因为同场景
允许多条 `active=0` 的历史/候选配置。激活事务执行：

```sql
UPDATE model_config SET active=0 WHERE scene=?;
UPDATE model_config SET active=1 WHERE id=? AND scene=?;
```

更新激活中的产业链配置时，服务会在写库前先构建新 chat/streaming 模型；构建失败则
事务不写入。写库成功后再通过 `afterCommit` 替换该场景引用，避免数据库回滚而内存已切换。
激活配置不能直接移动到另一场景，防止旧场景失去 active 或目标场景出现双 active。

同一供应商模型用于多个场景时仍需分别创建配置记录；记录与 scene 是一对一关系。例如
DeepSeek 分流需要为 `chain_agent_stream`、`chain_extraction`、`chain_report` 各建一条
`chat` 配置，不能让一条记录同时在三个场景激活。

## 共享契约

`com.sparrow.common.ai.model` 包包含：

- `ModelScene`：固定场景、展示名、期望 `ModelKind` 和 JSON/db 稳定值；
- `ModelKind`：`chat` / `embedding`；
- `ModelConfigRecord`：跨服务数据形状，不负责加解密，`toString()` 固定隐藏 API Key；
- `ModelConfigRules`：名称、连接参数、数值边界和 scene-kind 兼容校验；
- `ModelPoolHeaders`：内部鉴权请求头契约。

`ModelConfig` 的 `apiKeyPlain` 使用 `@JsonIgnore`，即使错误地进入 Web 序列化也不会回传；
管理端列表只返回 `apiKeyMasked`，JSON 字段使用稳定的 `scene` 和 `modelKind` 小写值。

## 管理端写入与测试

公开管理接口保持在 `/api/chains/admin/model-configs/**`，所有操作继续通过
`UserContext` + `/internal/user/{id}/profile` 校验管理员身份。

保存规则：

- 新建必须提供 API Key；更新留空表示保留旧密钥。
- Base URL 改变时禁止复用旧密钥。
- 未知 scene/kind、scene-kind 不匹配、越界 token/timeout/retry 均拒绝。
- embedding 场景将 `maxTokens` 归零且使用 `OpenAiEmbeddingModel` 测试，chat 场景使用
  `OpenAiChatModel` 测试。
- 连接测试服务端最多等待 15 秒、禁止重试；浏览器在 18 秒停止等待并允许用户主动取消。

前端按六个固定场景分组展示。场景选择会自动锁定模型类型；激活中的配置不可移动场景。
激活确认和成功提示会明确说明产业链立即生效、`sparrow-ai` 重启后生效。

OpenAI 兼容请求的供应商参数保持白名单化：智谱 `glm-*` 与官方 DeepSeek endpoint 上的
`deepseek-v4-*` 会发送 `thinking.type=disabled`，以控制多 Agent 工作流首包与总时延；其他
endpoint/model（包括旧 `deepseek-chat` 名称）不发送该私有参数。

## 内部读取契约与安全

接口：

```text
GET /internal/chains/model-configs/active?scene={scene}
X-Sparrow-Model-Pool-Token: <deployment secret>
```

返回 `ApiResponse<ModelConfigRecord>`，其中 API Key 是消费方构建模型所需的明文。
安全控制：

1. 路径位于 `/internal/**`，不匹配网关仅公开的 `/api/chains/**` 路由。
2. 两个服务必须从环境变量 `SPARROW_MODEL_POOL_INTERNAL_TOKEN` 注入相同、至少 32 字符的高熵值；服务端使用
   常量时间比较。服务端令牌缺失时返回 503，错误/缺失令牌返回 403。
3. 内部查询只允许两个 `sparrow-ai` 场景，产业链场景返回 400。
4. 响应对象和异常响应体不得记录；消费方日志只含 scene、source、configId 和异常类型。
5. 明文只存在于 owner 解密后的短生命周期对象、内部传输和消费服务内存，不落库、不进入
   浏览器、不进入 URL 查询参数。

部署仍需用内网 ACL 限制服务互访，并在生产流量层启用 TLS/mTLS。共享令牌是应用层
纵深防护，不替代传输加密和网络隔离。

## `sparrow-ai` 启动与降级

`AiConfig` 先构建一个启动期 `SparrowAiModelSelection` 快照：

1. 分别读取 `sparrow_ai_chat` 和 `sparrow_ai_embedding`，chat/streaming 共用 chat 快照。
2. Feign 连接超时 2 秒、读取超时 3 秒，禁止输出 Feign 日志正文。
3. 校验响应 code、active、scene、kind、API Key 和数值边界。
4. 单个场景读取失败时，只对该场景使用 `sparrow.ai.*` 环境配置兜底；另一场景不受影响。
5. 远端与环境配置都无效时，对应 Bean 为 null，现有 `AiService` 规则降级路径继续工作。

环境配置是灾难恢复兜底，不是第二个管理权威源。部署模型池前必须在管理页为两个
`sparrow-ai` 场景创建并激活配置；未完成时服务仍保持升级前的环境配置行为。

## 失败语义与回退

| 故障 | 行为 | 运维动作 |
| --- | --- | --- |
| owner/Nacos 不可达 | 超时后按场景使用环境配置 | 恢复服务发现或 owner，重启 `sparrow-ai` 验证 remote source |
| 内部令牌缺失/不一致 | owner 拒绝；consumer 不泄露令牌并走环境兜底 | 同步 `SPARROW_MODEL_POOL_INTERNAL_TOKEN` 后重启 |
| 远端场景/类型/参数无效 | 拒绝远端配置并走环境兜底 | 修正管理页配置并重新激活 |
| 产业链新模型无法构建 | 激活/更新事务失败，旧模型继续服务 | 测试连接、修正 endpoint/model 参数 |
| embedding 被配置为 chat | 保存和读取均拒绝 | 选择固定 embedding 场景，类型由 UI 自动联动 |
| `sparrow-ai` 场景被激活 | DB 权威项立即变化，运行实例暂不变 | 在维护窗口滚动重启 `sparrow-ai` |

数据库回退无需删列。回滚应用版本后，新列和新场景记录可保留；旧版本只会使用其原有
配置路径。严禁为回退删除 volume 或明文导出 API Key。

## 分步落地状态

| 步骤 | 状态 | 结果 |
| --- | --- | --- |
| 1. additive schema + common 契约 | 完成 | 两列、幂等迁移、固定枚举/校验/脱敏契约 |
| 2. industry-chain 多场景 | 完成 | 四场景装配、消费方映射、按场景激活和 afterCommit swap |
| 3. 管理端场景化 | 完成 | 分组、联动表单、chat/embedding 测试和真实生效提示 |
| 4. 内部读取契约 | 完成 | `/internal/**`、场景白名单、共享令牌、明文日志防护 |
| 5. sparrow-ai 接入 | 完成 | chat/streaming/embedding 启动快照、远端校验、环境兜底 |
| 6. 部署迁移与 smoke | 部分完成 | 迁移两次成功、六场景唯一激活、remote/fallback/recovery、真实 chat/SSE/规划成功；完整产业链与 embedding 待收口 |

## 验证要求

代码交付前至少运行：

```text
mvn -f backend/pom.xml -B -ntp -pl sparrow-ai,sparrow-industry-chain -am test
npm run build
node tools/harness.mjs guard
node tools/harness.mjs changed
git diff --check
```

部署 smoke：

1. 重复执行 `backend/scripts/migrate-model-config-scene.sql` 两次，第二次无错误。
2. 配置相同的 `SPARROW_MODEL_POOL_INTERNAL_TOKEN`，创建并激活六个场景。
3. 重启 `sparrow-ai`，日志显示两个场景 `source=remote`，日志和响应中无明文 API Key。
4. 分别执行 chat、streaming Agent、embedding/RAG、规划、抽取、报告和流式总结。
5. 停止 owner 后重启 `sparrow-ai`，验证在超时边界内使用 `source=environment`；恢复后再次
   重启并验证回到 remote。
6. 先排空进行中的调研，再激活新的产业链配置；验证后续请求切换且数据库 active 与内存日志一致。

2026-07-14 本地部署证据：

- `.env` 中生成了 64 字符高熵 `SPARROW_MODEL_POOL_INTERNAL_TOKEN`，Compose 脱敏解析确认两端一致；
  API Key 和内部令牌均未出现在服务日志中。
- MySQL 8.0.46 上用显式 UTF-8 输入连续执行迁移两次成功；六个 scene-kind 组合均有且仅有一条 active。
- `sparrow-ai` 启动时 chat/embedding 均为 `source=remote`；owner 停机后重启均为
  `source=environment`，owner 恢复后再次重启均回到 `source=remote`。
- 供应商资源包确认后，`glm-4.7` 标准请求返回 HTTP 200（23.778 秒）；真实 `/api/ai/ask`
  以 Agent 模式返回 2360 字符，SSE 路径收到 282 个 delta 和 `done`，均未出现 error 事件。
- `chain_planning` 真实调用返回 584 字符（44.742 秒）。完整调研已通过 planning、searching、
  verifying、mapping 并进入 writing；Agent 并发阶段遇到供应商 code `1302` 限流后执行了
  stream → blocking 回退，最终报告仍因模型响应超时失败。
- 因此计划保留 `chain_planning=glm-4.7`，把 `chain_agent_stream`、`chain_extraction`、
  `chain_report` 分流到三条独立的 `deepseek-v4-flash/chat` 配置，排空任务后再激活和重跑。
- `embedding-3` 仍返回 HTTP 429 / code `1113`（余额不足或无可用资源包）；GLM 推理资源包不覆盖
  embedding。该路径必须在补充 embedding 资源后单独复测，不能由 chat 成功推断为通过。

## 已知限制与后续演进

- `sparrow-ai` 本版不是运行时热刷新。若业务要求秒级切换，优先引入带版本号的配置事件或
  有界轮询，并用原子 provider 包装现有 Bean；不要让 owner 反向调用 consumer。
- 多实例 `sparrow-industry-chain` 目前只切换处理管理请求的实例。扩容前需加入带 configId/scene
  的广播或轮询，并验证幂等、乱序和重放。
- 产业链模型池按单次调用读取引用，没有按 `runId` 固定四场景快照。已发出的调用通常继续使用
  原模型，但任务后续阶段可能读到新模型；切换前应排空任务。若产品要求整条任务强一致，需引入
  run 级模型版本快照，并把 chat/streaming 两个引用作为一组原子发布。
- active 唯一性依赖事务。若未来存在绕过应用的写入方，应增加数据库可表达的约束方案或
  定期一致性检查，而不是简单建立会阻止多条 inactive 的唯一索引。
- 生产必须补做真实模型、真实数据库迁移和 TLS/mTLS smoke；自动化测试不证明外部供应商兼容性。
