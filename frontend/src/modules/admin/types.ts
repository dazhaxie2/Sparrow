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
  /** 模型池场景(dbValue),如 chain_planning。 */
  scene: string
  /** 模型类型(dbValue):chat / embedding。 */
  modelKind: string
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
  /** 模型池场景(dbValue)。 */
  scene: string
  /** 模型类型(dbValue):chat / embedding。 */
  modelKind: string
}

/**
 * 模型池场景枚举(与后端 sparrow-common ModelScene 的 dbValue 一致)。
 * 中文 label 供前端展示。新增场景时同步更新后端枚举与 model_config 表。
 */
export const MODEL_SCENES: ReadonlyArray<{ value: string; label: string; kind: 'chat' | 'embedding' }> = [
  { value: 'sparrow_ai_chat', label: '科技图 AI 对话', kind: 'chat' },
  { value: 'sparrow_ai_embedding', label: '科技图向量检索', kind: 'embedding' },
  { value: 'chain_planning', label: '调研规划与问答', kind: 'chat' },
  { value: 'chain_extraction', label: '图谱抽取', kind: 'chat' },
  { value: 'chain_report', label: '报告生成', kind: 'chat' },
  { value: 'chain_agent_stream', label: 'Agent 流式总结', kind: 'chat' },
]

export const MODEL_KINDS: ReadonlyArray<{ value: string; label: string }> = [
  { value: 'chat', label: '对话模型' },
  { value: 'embedding', label: '向量模型' },
]

/** 按 scene dbValue 取中文 label;未知值原样返回。 */
export function sceneLabel(scene: string): string {
  return MODEL_SCENES.find(s => s.value === scene)?.label ?? scene
}

/** 按 modelKind dbValue 取中文 label;未知值原样返回。 */
export function kindLabel(kind: string): string {
  return MODEL_KINDS.find(k => k.value === kind)?.label ?? kind
}

export function kindForScene(scene: string): 'chat' | 'embedding' {
  return MODEL_SCENES.find(item => item.value === scene)?.kind ?? 'chat'
}

export interface TestConfigPayload {
  name: string
  baseUrl: string
  modelName: string
  /** 空 = 复用当前激活配置的 key。 */
  apiKey: string
  timeoutSeconds: number
  scene: string
  modelKind: string
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

export interface AgentProfile {
  service: 'sparrow-ai' | 'sparrow-industry-chain'
  agentKey: string
  displayName: string
  description: string
  systemPrompt: string
  enabled: boolean
  maxContextMessages: number
  maxContextChars: number
  maxOutputChars: number
  maxSteps: number
  updatedBy: number | null
  updatedAt: string | null
}

export interface SaveAgentProfilePayload {
  agentKey: string
  systemPrompt: string
  enabled: boolean
  maxContextMessages: number
  maxContextChars: number
  maxOutputChars: number
  maxSteps: number
}
