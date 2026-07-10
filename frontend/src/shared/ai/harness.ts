export interface AiHarnessEvent {
  stage: string
  status: string
  detail: string
  occurredAt: string
}

export interface AiHarnessMetadata {
  schemaVersion: number
  traceId: string
  surface: string
  status: 'running' | 'completed' | 'degraded' | 'failed' | string
  currentStage: string
  retryable: boolean
  fallbackUsed: boolean
  contextMessages: number
  events: AiHarnessEvent[]
  warnings: string[]
}

