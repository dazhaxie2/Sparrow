/**
 * SSE 客户端:基于 fetch + ReadableStream,支持 POST + Bearer token。
 *
 * <p>不用原生 EventSource:它无法携带 Authorization 头,且只支持 GET。
 * 这里按 SSE 规范解析 `event: <name>\ndata: <json>\n\n` 帧,
 * 把每帧拆成 { event, data } 投递给 onEvent。</p>
 *
 * <p>同时保留向后兼容的 onMessage(裸文本),供仍按旧协议的调用方使用。</p>
 */
export interface SSEHandlers {
  /** 结构化事件:每解析出一个完整帧触发一次,event=事件名,data=解析后的 JSON(解析失败则原文)。 */
  onEvent?: (event: string, data: any) => void
  /** 裸文本回调:每个网络分片触发一次(向后兼容)。 */
  onMessage?: (chunk: string) => void
  onDone?: () => void
  onError?: (error: Error) => void
}

export function createSSEConnection(url: string, body: unknown, handlers: SSEHandlers) {
  const controller = new AbortController()
  const token = localStorage.getItem('sparrow_token')

  fetch(url, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Accept: 'text/event-stream',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    body: JSON.stringify(body),
    signal: controller.signal,
  })
    .then(async (response) => {
      if (!response.ok) {
        // 非 2xx:尝试读出业务错误信息(如 429 配额耗尽)。
        let message = `服务暂不可用（HTTP ${response.status}）`
        try {
          const errBody = await response.json()
          if (errBody?.message) message = errBody.message
        } catch {
          /* 响应体非 JSON,沿用默认文案 */
        }
        const err = new Error(message) as Error & { code: number }
        err.code = response.status
        throw err
      }
      if (!response.body) return
      const reader = response.body.getReader()
      const decoder = new TextDecoder()
      let buffer = ''
      while (true) {
        const { done, value } = await reader.read()
        if (done) break
        const chunk = decoder.decode(value, { stream: true })
        if (handlers.onMessage) handlers.onMessage(chunk)
        buffer += chunk
        // 一个 SSE 帧以空行(\n\n)结尾;buffer 可能含多个帧或半帧,
        // 按 \n\n 切分,只有完整的帧才解析,半帧留到下次。
        const frames = buffer.split('\n\n')
        buffer = frames.pop() ?? ''
        for (const frame of frames) {
          const parsed = parseFrame(frame)
          if (parsed) handlers.onEvent?.(parsed.event, parsed.data)
        }
      }
      // 收尾:解析缓冲区里残余的最后一帧(部分实现最后一帧后没有 \n\n)。
      if (buffer.trim()) {
        const parsed = parseFrame(buffer)
        if (parsed) handlers.onEvent?.(parsed.event, parsed.data)
      }
      handlers.onDone?.()
    })
    .catch((err) => {
      // abort 不算错误
      if (err?.name === 'AbortError') return
      handlers.onError?.(err)
    })

  return () => controller.abort()
}

/** 解析单个 SSE 帧:聚合 data: 行为 payload,event: 行为事件名(缺省为 message)。 */
function parseFrame(frame: string): { event: string; data: any } | null {
  const lines = frame.split('\n')
  let event = 'message'
  const dataLines: string[] = []
  for (const line of lines) {
    if (line.startsWith('event:')) {
      event = line.slice(6).trim()
    } else if (line.startsWith('data:')) {
      dataLines.push(line.slice(5).trimStart())
    }
    // 忽略 id:/retry:/注释行(:)
  }
  if (dataLines.length === 0) return null
  const payload = dataLines.join('\n')
  let data: any = payload
  try {
    data = JSON.parse(payload)
  } catch {
    /* 非 JSON,保留原文 */
  }
  return { event, data }
}
