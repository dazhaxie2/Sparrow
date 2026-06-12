import { post } from '../../../shared/api/request'
import type { AskResult } from '../types'

export function askAi(question: string) {
  return post<AskResult>('/api/ai/ask', { question })
}
