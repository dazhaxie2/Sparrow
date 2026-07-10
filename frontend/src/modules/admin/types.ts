export interface ModelConfig {
  id: number | null
  name: string
  baseUrl: string
  modelName: string
  /** 后端返回的脱敏 key,如 sk-1****cdef。编辑时空值表示保留旧 key。 */
  apiKeyMasked: string
  maxTokens: number
  timeoutSeconds: number
  maxRetries: number
  active: boolean
  createdBy: number | null
  createdAt: string | null
  updatedAt: string | null
}

export interface ModelConfigAudit {
  id: number
  configId: number | null
  configName: string | null
  operatorId: number | null
  action: string
  summary: string | null
  testOk: number | null
  createdAt: string
}

export interface SaveConfigPayload {
  id: number | null
  name: string
  baseUrl: string
  modelName: string
  /** 空 = 保留旧 key;非空 = 更新。 */
  apiKey: string
  maxTokens: number
  timeoutSeconds: number
  maxRetries: number
}

export interface TestConfigPayload {
  name: string
  baseUrl: string
  modelName: string
  /** 空 = 复用当前激活配置的 key。 */
  apiKey: string
  timeoutSeconds: number
}

export interface TestResult {
  ok: boolean
  message: string
  reply: string | null
}

/** 审计动作类型(与后端 ModelConfigService.AuditAction 约定一致)。 */
export const MODEL_CONFIG_ACTION = {
  TEST: 'TEST',
  SAVE: 'SAVE',
  ACTIVATE: 'ACTIVATE',
} as const
