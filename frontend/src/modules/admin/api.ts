import { get, post } from '../../shared/api/request'
import type {
  AgentProfile,
  ModelConfig,
  ModelConfigAudit,
  SaveAgentProfilePayload,
  SaveConfigPayload,
  TestConfigPayload,
  TestResult,
} from './types'

/** 列出全部模型配置(api_key 脱敏)。 */
export function listModelConfigs() {
  return get<ModelConfig[]>('/api/chains/admin/model-configs')
}

/** 测试连接(不落库)。 */
export function testModelConfig(payload: TestConfigPayload) {
  return post<TestResult>('/api/chains/admin/model-configs/test', payload)
}

/** 保存(新增或更新)。 */
export function saveModelConfig(payload: SaveConfigPayload) {
  return post<{ id: number }>('/api/chains/admin/model-configs', payload)
}

/** 激活指定配置(原子热切换)。 */
export function activateModelConfig(configId: number) {
  return post<{ ok: boolean }>(`/api/chains/admin/model-configs/${configId}/activate`, {})
}

/** 审计记录。 */
export function listAudits(limit = 50) {
  return get<ModelConfigAudit[]>(`/api/chains/admin/model-configs/audits?limit=${limit}`)
}

export async function listAgentProfiles() {
  const [general, industry] = await Promise.all([
    get<AgentProfile[]>('/api/ai/admin/agent-configs'),
    get<AgentProfile[]>('/api/chains/admin/agent-configs'),
  ])
  return [...general, ...industry]
}

export function saveAgentProfile(service: AgentProfile['service'], payload: SaveAgentProfilePayload) {
  const path = service === 'sparrow-ai'
    ? '/api/ai/admin/agent-configs'
    : '/api/chains/admin/agent-configs'
  return post<AgentProfile>(path, payload)
}
