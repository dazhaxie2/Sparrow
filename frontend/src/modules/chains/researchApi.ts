import { get, post, request } from '../../shared/api/request'
import type {
  AttachmentRequest,
  ForumEventView,
  ResearchCardDetail,
  ResearchCardSummary,
  ResearchMessageReply,
  ResearchRun,
  ResearchSource,
  ResearchStartResult,
} from './researchTypes'

const ROOT = '/api/ai/chain-research/cards'

export function fetchResearchCards() {
  return get<ResearchCardSummary[]>(ROOT)
}

export function createResearchCard(title: string, brief: string, sources?: AttachmentRequest[]) {
  return post<ResearchCardDetail>(ROOT, { title, brief, sources })
}

export function fetchResearchCard(id: number) {
  return get<ResearchCardDetail>(`${ROOT}/${id}`)
}

/** 拉取卡片最近一次运行的论坛事件(工作台初次加载还原协作流)。 */
export function fetchForumEvents(cardId: number) {
  return get<ForumEventView[]>(`${ROOT}/${cardId}/forum`)
}

export function updateResearchCard(id: number, title: string, brief: string, sources?: AttachmentRequest[]) {
  return request<ResearchCardDetail>(`${ROOT}/${id}`, {
    method: 'PUT',
    body: JSON.stringify({ title, brief, sources }),
  })
}

export function deleteResearchCard(id: number) {
  return request<void>(`${ROOT}/${id}`, { method: 'DELETE' })
}

export function sendResearchMessage(id: number, content: string) {
  return post<ResearchMessageReply>(`${ROOT}/${id}/messages`, { content })
}

export function startResearchRun(id: number) {
  return post<ResearchStartResult>(`${ROOT}/${id}/runs`, {})
}

export function fetchResearchRun(cardId: number, runId: number) {
  return get<ResearchRun>(`${ROOT}/${cardId}/runs/${runId}`)
}

export function cancelResearchRun(cardId: number, runId: number) {
  return post<void>(`${ROOT}/${cardId}/runs/${runId}/cancel`, {})
}

export async function uploadResearchAttachment(cardId: number, file: File): Promise<ResearchSource> {
  const API_BASE = import.meta.env.VITE_API_BASE || ''
  const token = localStorage.getItem('sparrow_token')
  const formData = new FormData()
  formData.append('file', file)

  const res = await fetch(`${API_BASE}${ROOT}/${cardId}/attachments/upload`, {
    method: 'POST',
    headers: token ? { Authorization: `Bearer ${token}` } : {},
    body: formData,
  })

  if (!res.ok) {
    const err = new Error(`上传失败（HTTP ${res.status}）`) as Error & { code: number }
    err.code = res.status || 500
    throw err
  }

  const body = await res.json()
  if (body.code !== 0) {
    const err = new Error(body.message || '上传失败') as Error & { code: number }
    err.code = body.code
    throw err
  }
  return body.data
}

export async function streamResearchEvents(
  cardId: number,
  onEvent: (event: string, data: Record<string, unknown>) => void,
  signal: AbortSignal,
) {
  const base = import.meta.env.VITE_API_BASE || ''
  const token = localStorage.getItem('sparrow_token')
  const response = await fetch(`${base}${ROOT}/${cardId}/events`, {
    headers: token ? { Authorization: `Bearer ${token}` } : {},
    signal,
  })
  if (!response.ok || !response.body) throw new Error(`事件流连接失败（HTTP ${response.status}）`)

  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  while (!signal.aborted) {
    const { done, value } = await reader.read()
    if (done) break
    buffer += decoder.decode(value, { stream: true })
    const chunks = buffer.split(/\r?\n\r?\n/)
    buffer = chunks.pop() || ''
    for (const chunk of chunks) {
      let event = 'message'
      const data: string[] = []
      for (const line of chunk.split(/\r?\n/)) {
        if (line.startsWith('event:')) event = line.slice(6).trim()
        if (line.startsWith('data:')) data.push(line.slice(5).trim())
      }
      if (!data.length) continue
      try {
        onEvent(event, JSON.parse(data.join('\n')))
      } catch {
        // 忽略服务端心跳或非 JSON 事件。
      }
    }
  }
}
