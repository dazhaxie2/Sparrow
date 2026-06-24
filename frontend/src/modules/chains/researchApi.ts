import { get, post, request } from '../../shared/api/request'
import type {
  ResearchCardDetail,
  ResearchCardSummary,
  ResearchMessageReply,
  ResearchRun,
  ResearchStartResult,
} from './researchTypes'

const ROOT = '/api/ai/chain-research/cards'

export function fetchResearchCards() {
  return get<ResearchCardSummary[]>(ROOT)
}

export function createResearchCard(title: string, brief: string) {
  return post<ResearchCardDetail>(ROOT, { title, brief })
}

export function fetchResearchCard(id: number) {
  return get<ResearchCardDetail>(`${ROOT}/${id}`)
}

export function updateResearchCard(id: number, title: string, brief: string) {
  return request<ResearchCardDetail>(`${ROOT}/${id}`, {
    method: 'PUT',
    body: JSON.stringify({ title, brief }),
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
