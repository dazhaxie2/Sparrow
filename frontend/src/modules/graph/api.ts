import { del, get, post, put } from '../../shared/api/request'
import type {
  Tree, NodeDetail, NodeBrief, Overview, Neighborhood, SubgraphFilters, GraphTile, ClusterOverview,
} from './types'

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

/** 服务端预计算坐标的 LOD 瓦片：L0 为全景代表点，L1-L3 为指定簇。 */
export function fetchTile(level: number, clusterId = 0) {
  return get<GraphTile>(`/api/graph/tiles/${level}/${clusterId}`)
}

/** 社区簇总览，包含簇成员数与代表节点。 */
export function fetchClusterOverview() {
  return get<ClusterOverview>('/api/graph/clusters')
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

/** 节点「应用与产业链」:服务端命中缓存秒出,未命中时 AI 判定后缓存。 */
export function fetchApplications(id: number) {
  return get<NodeBrief[]>(`/api/graph/node/${id}/applications`)
}

// ── 收藏夹 ──

export interface FavoriteFolder {
  id: number
  name: string
  sortOrder: number
}

export interface FavoriteNodeRef {
  id: number
  name: string
  era: string
  yearLabel: string
}

export interface FavoriteFolderDetail extends FavoriteFolder {
  nodes: FavoriteNodeRef[]
}

export function fetchFavoriteFolders() {
  return get<FavoriteFolder[]>('/api/graph/favorites/folders')
}

export function fetchFavoriteDetails() {
  return get<FavoriteFolderDetail[]>('/api/graph/favorites/details')
}

export function createFavoriteFolder(name: string) {
  return post<FavoriteFolder>('/api/graph/favorites/folders', { name })
}

export function renameFavoriteFolder(id: number, name: string) {
  return put<FavoriteFolder>(`/api/graph/favorites/folders/${id}`, { name })
}

export function deleteFavoriteFolder(id: number) {
  return del(`/api/graph/favorites/folders/${id}`)
}

export function favoriteNode(folderId: number, nodeId: number) {
  return post('/api/graph/favorites/items', { folderId, nodeId })
}

export function moveFavoriteNode(nodeId: number, targetFolderId: number) {
  return put('/api/graph/favorites/items/move', { nodeId, targetFolderId })
}

export function unfavoriteNode(nodeId: number) {
  return del(`/api/graph/favorites/items/${nodeId}`)
}
