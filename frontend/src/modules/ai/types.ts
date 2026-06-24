export interface SourceRef {
  id: number
  name: string
  url?: string | null
}

export interface AgentStep {
  key: string
  label: string
  status: 'done' | 'partial' | 'skipped' | 'failed' | string
}

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
}

export interface AskResult {
  answer: string
  mode: string
  format: string
  intent: string
  sources: SourceRef[]
  steps: AgentStep[]
  remainingQuota: number
}

export interface ChatSession {
  id: string
  title: string
  messages: ChatMessage[]
  createdAt: number
}
