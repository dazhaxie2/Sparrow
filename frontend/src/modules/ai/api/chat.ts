import { post } from '../../../shared/api/request'
import { createSSEConnection } from '../../../shared/utils/sse'
import type { AgentStep, AskResult, SourceRef } from '../types'

export function askAi(question: string) {
  return post<AskResult>('/api/ai/ask', { question })
}

/** 流式问答首帧 meta 事件载荷。 */
export interface StreamMeta {
  mode: string
  intent: string
  sources: SourceRef[]
  steps: AgentStep[]
  remainingQuota: number
}

/** 流式问答的事件回调集合。 */
export interface StreamHandlers {
  onMeta?: (meta: StreamMeta) => void
  /** 思考过程增量(reasoning 模型才有)。 */
  onThinking?: (delta: string) => void
  /** 正文增量。 */
  onDelta?: (delta: string) => void
  /** 结束(mode/format 最终确认)。 */
  onDone?: (mode: string, format: string) => void
  /** 错误(含降级到规则仍失败)。 */
  onError?: (message: string) => void
}

/**
 * 流式问答:订阅 /api/ai/ask/stream,按 SSE 事件名分流回调。
 * 返回 abort 函数,用于取消(如组件卸载/切换问题)。
 */
export function streamAsk(question: string, handlers: StreamHandlers): () => void {
  return createSSEConnection('/api/ai/ask/stream', { question }, {
    onEvent(event, data) {
      switch (event) {
        case 'meta':
          handlers.onMeta?.(data as StreamMeta)
          break
        case 'thinking':
          if (typeof data?.text === 'string') handlers.onThinking?.(data.text)
          break
        case 'delta':
          if (typeof data?.text === 'string') handlers.onDelta?.(data.text)
          break
        case 'done':
          handlers.onDone?.(data?.mode ?? 'rules', data?.format ?? 'markdown:v1')
          break
        case 'error':
          handlers.onError?.(data?.message ?? '生成失败')
          break
        default:
          break
      }
    },
    onError(err) {
      handlers.onError?.(err.message || '网络错误')
    },
  })
}
