export interface SourceRef {
  id: number
  name: string
}

export interface ChatMessage {
  role: 'user' | 'assistant' | 'system'
  content: string
  sources?: SourceRef[]
  timestamp?: number
}

export interface AskResult {
  answer: string
  mode: string
  sources: SourceRef[]
  remainingQuota: number
}

export interface ChatSession {
  id: string
  title: string
  messages: ChatMessage[]
  createdAt: number
}
