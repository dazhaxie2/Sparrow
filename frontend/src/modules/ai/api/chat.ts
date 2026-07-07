import { get, post, request } from '../../../shared/api/request'
import { createSSEConnection } from '../../../shared/utils/sse'
import type { AgentStep, AskResult, ChatSession, SourceRef } from '../types'

export function askAi(question: string) {
  return post<AskResult>('/api/ai/ask', { question })
}

// ==================== 历史会话管理 ====================

/** 会话列表项(后端 SessionItem)。 */
export interface SessionItem {
  id: number
  title: string
  messageCount: number
  createdAt: string
  updatedAt: string
}

/** 创建会话的响应。 */
export interface CreateSessionResponse {
  sessionId: number
}

/** 历史消息项(后端 MessageItem),用于回放历史会话。 */
export interface MessageItem {
  id: number
  role: string
  content: string
  mode: string | null
  createdAt: string
}

export function listSessions() {
  return get<SessionItem[]>('/api/ai/sessions')
}

export function createSession(question: string) {
  return post<CreateSessionResponse>('/api/ai/sessions', { question })
}

export function getSessionMessages(id: number) {
  return get<MessageItem[]>(`/api/ai/sessions/${id}/messages`)
}

export function deleteSession(id: number) {
  return request<void>(`/api/ai/sessions/${id}`, { method: 'DELETE' })
}

// ==================== 流式问答 ====================

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
 *
 * @param question 用户问题
 * @param handlers 事件回调
 * @param sessionId 会话 id,可选;非空时后端把本次问答落库到该会话
 */
export function streamAsk(
  question: string,
  handlers: StreamHandlers,
  sessionId?: number | null,
): () => void {
  // settled:业务 done/error 已处理过就置 true,避免传输层 onDone 兜底时重复触发收尾。
  // 必要性:后端正常路径必发 done/error,但边界情况(agent+rag 两级连续悬挂导致 SseEmitter
  // 120s 超时先于降级完成)会让 done/error 都发不出。此时若传输层 onDone 不兜底,
  // 前端 loading 永远为 true、消息永久卡在 streaming 态(## 不渲染、光标/占位框不消失)。
  let settled = false
  return createSSEConnection('/api/ai/ask/stream', { question, sessionId: sessionId ?? null }, {
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
          if (!settled) {
            settled = true
            handlers.onDone?.(data?.mode ?? 'rules', data?.format ?? 'markdown:v1')
          }
          break
        case 'error':
          if (!settled) {
            settled = true
            handlers.onError?.(data?.message ?? '生成失败')
          }
          break
        default:
          break
      }
    },
    onError(err) {
      if (!settled) {
        settled = true
        handlers.onError?.(err.message || '网络错误')
      }
    },
    onDone() {
      // 传输层连接结束(流读尽/连接关闭)。若业务 done/error 未到(后端漏发或网络中断),
      // 兜底触发 onDone,保证前端必定收尾。已 settled 则跳过,避免重复 resolve。
      if (!settled) {
        settled = true
        handlers.onDone?.('rules', 'markdown:v1')
      }
    },
  })
}
