import { get } from './http'

export interface NodeBrief {
  id: number
  code: string
  name: string
  era: string
  eraRank: number
  yearLabel: string
  summary: string
  premium: boolean
}

export interface EdgeBrief {
  from: number
  to: number
}

export interface Tree {
  nodes: NodeBrief[]
  edges: EdgeBrief[]
}

export interface NodeDetail {
  id: number
  code: string
  name: string
  era: string
  eraRank: number
  yearLabel: string
  summary: string
  detail: string | null
  premium: boolean
  locked: boolean
  prerequisites: NodeBrief[]
  unlocks: NodeBrief[]
}

export function fetchTree() {
  return get<Tree>('/api/graph/tree')
}

export function fetchNode(id: number) {
  return get<NodeDetail>(`/api/graph/node/${id}`)
}

export function fetchPrerequisites(id: number) {
  return get<NodeBrief[]>(`/api/graph/node/${id}/prerequisites`)
}
