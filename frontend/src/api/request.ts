const API_BASE = import.meta.env.VITE_API_BASE || ''

export interface ApiResponse<T> {
  code: number
  message: string
  data: T
}

export async function request<T>(path: string, options?: RequestInit): Promise<T> {
  const headers: Record<string, string> = {
    'Content-Type': 'application/json',
    ...(options?.headers as Record<string, string> || {}),
  }
  const token = localStorage.getItem('sparrow_token')
  if (token) headers['Authorization'] = `Bearer ${token}`

  const res = await fetch(`${API_BASE}${path}`, { ...options, headers })
  const body: ApiResponse<T> = await res.json()
  if (body.code !== 0) {
    const err = new Error(body.message || '请求失败') as Error & { code: number }
    err.code = body.code
    throw err
  }
  return body.data
}

export function post<T>(path: string, data: unknown): Promise<T> {
  return request<T>(path, { method: 'POST', body: JSON.stringify(data) })
}

export function get<T>(path: string): Promise<T> {
  return request<T>(path)
}
