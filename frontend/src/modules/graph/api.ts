import { get } from '../../shared/api/request'
import type {
  Tree, NodeDetail, NodeBrief, KnowledgeStatus, Overview, Neighborhood, SubgraphFilters,
} from './types'

export function fetchTree() {
  return get<Tree>('/api/graph/tree')
}

/** 领域×时代聚合总览(响应体小,展示真实规模 + 领域列表)。 */
export function fetchOverview() {
  return get<Overview>('/api/graph/overview')
}

/** 有界子图:过滤后取前 limit 重要节点 + 其间的边(默认/领域/时代视图)。 */
export function fetchSubgraph(filters: SubgraphFilters = {}) {
  const params = new URLSearchParams()
  if (filters.category) params.set('category', filters.category)
  if (filters.eraRank != null) params.set('eraRank', String(filters.eraRank))
  if (filters.q) params.set('q', filters.q)
  if (filters.minImportance != null) params.set('minImportance', String(filters.minImportance))
  params.set('limit', String(filters.limit ?? 400))
  return get<Tree>(`/api/graph/subgraph?${params.toString()}`)
}

/** 节点邻域子图(展开式浏览)。 */
export function fetchNeighborhood(id: number) {
  return get<Neighborhood>(`/api/graph/node/${id}/neighborhood`)
}

/** 名称/摘要检索(服务端,适配万级规模)。 */
export function searchNodes(q: string, limit = 12) {
  return get<NodeBrief[]>(`/api/graph/search?q=${encodeURIComponent(q)}&limit=${limit}`)
}

export function fetchNode(id: number) {
  return get<NodeDetail>(`/api/graph/node/${id}`)
}

export function fetchPrerequisites(id: number) {
  return get<NodeBrief[]>(`/api/graph/node/${id}/prerequisites`)
}

export function fetchKnowledgeStatus() {
  return get<KnowledgeStatus>('/api/graph/knowledge/status')
}
