import { post } from './http'

export interface SourceRef {
  id: number
  name: string
}

export interface AskResult {
  answer: string
  mode: string
  sources: SourceRef[]
  remainingQuota: number
}

export function askAi(question: string) {
  return post<AskResult>('/api/ai/ask', { question })
}
