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
