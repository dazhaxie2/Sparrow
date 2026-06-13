import { get } from '../../shared/api/request'
import type { Tree, NodeDetail, NodeBrief, KnowledgeStatus } from './types'

export function fetchTree() {
  return get<Tree>('/api/graph/tree')
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
