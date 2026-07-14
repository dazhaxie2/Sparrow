import type { ApiResponse } from '../types/common'

const API_BASE = import.meta.env.VITE_API_BASE || ''

export async function request<T>(path: string, options?: RequestInit): Promise<T> {
  const headers: Record<string, string> = {
    'Content-Type': 'application/json',
    ...((options?.headers as Record<string, string>) || {}),
  }
  const token = localStorage.getItem('sparrow_token')
  if (token) headers.Authorization = `Bearer ${token}`

  const res = await fetch(`${API_BASE}${path}`, { ...options, headers })
  let body: ApiResponse<T>
  try {
    body = await res.json()
  } catch {
    const err = new Error(res.ok ? '服务返回了空响应，请稍后重试' : `服务暂不可用（HTTP ${res.status}）`) as Error & { code: number }
    err.code = res.status || 500
    throw err
  }
  if (body.code !== 0) {
    const err = new Error(body.message || '请求失败') as Error & { code: number }
    err.code = body.code
    throw err
  }
  return body.data
}

export function post<T>(
  path: string,
  data: unknown,
  options?: Omit<RequestInit, 'method' | 'body'>,
): Promise<T> {
  return request<T>(path, { ...options, method: 'POST', body: JSON.stringify(data) })
}

export function get<T>(path: string): Promise<T> {
  return request<T>(path)
}
