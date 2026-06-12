import { post } from '../request'
import type { AskResult } from '../../types/ai'

export function askAi(question: string) {
  return post<AskResult>('/api/ai/ask', { question })
}
