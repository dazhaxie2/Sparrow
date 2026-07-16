/**
 * 跨模块共享的 AI 问答契约与同步问答 API。
 *
 * askAi / createSession 及其返回类型(AskResult / SourceRef / AgentStep)是
 * AI 对话的公共契约,被 ai 与 graph(对话模式)等多个业务模块消费,放 shared
 * 以避免业务模块互相 import。ai 模块自己的完整 API(含流式 streamAsk、
 * 会话历史等)仍留在 modules/ai/api/chat,并通过 re-export 引用这里。
 */
import { get, post } from '../api/request'
import type { AiHarnessMetadata } from './harness'

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

export interface AskResult {
  answer: string
  mode: string
  format: string
  intent: string
  sources: SourceRef[]
  steps: AgentStep[]
  remainingQuota: number
  harness: AiHarnessMetadata
}

export function askAi(question: string, surface = 'graph-dialog', sessionId?: number | null) {
  return post<AskResult>('/api/ai/ask', { question, surface, sessionId: sessionId ?? null })
}

export interface CreateSessionResponse {
  sessionId: number
}

export function createSession(question: string) {
  return post<CreateSessionResponse>('/api/ai/sessions', { question })
}
