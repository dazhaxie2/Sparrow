import type { AiHarnessMetadata } from '../../shared/ai/harness'
export type { AiHarnessEvent, AiHarnessMetadata } from '../../shared/ai/harness'

// SourceRef / AgentStep / AskResult 已下沉到 shared/ai/chat(跨模块共享契约),
// 这里 re-export 以保持 ai 模块内部既有引用的连续性。
export type { SourceRef, AgentStep, AskResult } from '../../shared/ai/chat'
import type { SourceRef, AgentStep, AskResult } from '../../shared/ai/chat'

export interface ChatMessage {
  role: 'user' | 'assistant' | 'system'
  content: string
  /** reasoning 模型的思考过程(流式累加,仅 assistant 消息且模型吐 reasoning 时存在)。 */
  thinking?: string
  /** 流式中:正文是否仍在生成(用于显示闪烁光标、阻止重复操作)。 */
  streaming?: boolean
  mode?: string
  format?: string
  intent?: string
  sources?: SourceRef[]
  steps?: AgentStep[]
  timestamp?: number
  /** 后端历史消息的 id(前端临时消息无此字段)。 */
  id?: number
  /** 服务端运行时 Harness 摘要，用于阶段展示和故障追踪。 */
  harness?: AiHarnessMetadata
}

export interface ChatSession {
  /** 后端会话主键(自增 id)。 */
  id: number
  title: string
  /** 消息列表,仅在打开该会话时按需加载(列表视图只看元数据,不含消息)。 */
  messages?: ChatMessage[]
  createdAt: number
  updatedAt?: number
  messageCount?: number
}
