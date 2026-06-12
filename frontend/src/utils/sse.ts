export interface SSEOptions {
  onMessage: (chunk: string) => void
  onDone?: () => void
  onError?: (error: Error) => void
}

export function createSSEConnection(url: string, body: unknown, options: SSEOptions) {
  const controller = new AbortController()
  const token = localStorage.getItem('sparrow_token')

  fetch(url, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    body: JSON.stringify(body),
    signal: controller.signal,
  })
    .then(async (response) => {
      if (!response.body) return
      const reader = response.body.getReader()
      const decoder = new TextDecoder()
      while (true) {
        const { done, value } = await reader.read()
        if (done) break
        const chunk = decoder.decode(value, { stream: true })
        options.onMessage(chunk)
      }
      options.onDone?.()
    })
    .catch((err) => {
      options.onError?.(err)
    })

  return () => controller.abort()
}
