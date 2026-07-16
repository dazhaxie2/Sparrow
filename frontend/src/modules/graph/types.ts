// NodeBrief / EdgeBrief 是跨模块共享的基础节点契约,定义在 shared/types/graph,
// 这里 re-export 以保持 graph 内部与既有引用的连续性。
export type { NodeBrief, EdgeBrief } from '../../../shared/types/graph'
import type { NodeBrief, EdgeBrief } from '../../../shared/types/graph'

export interface Tree {
  nodes: NodeBrief[]
  edges: EdgeBrief[]
}

export interface TileNode {
  id: number
  clusterId: number
  x: number
  y: number
  name: string
  category?: string | null
  importance?: number | null
}

export interface GraphTile {
  level: number
  clusterId: number
  nodes: TileNode[]
  edges: EdgeBrief[]
}

export interface ClusterNode extends TileNode {
  nodeCount: number
}

export interface ClusterOverview {
  representedNodes: number
  clusters: ClusterNode[]
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
  /** 「应用与产业链」:材料/化合物类节点由 AI 按需判定后回填,普通节点恒为空。 */
  applications: NodeBrief[]
  sources: SourceBrief[]
  category?: string | null
  importance?: number | null
  /** 当前登录用户已将该节点收藏到的收藏夹 id；未登录或未收藏为 null。 */
  favoriteFolderId?: number | null
}

export interface EraBrief {
  eraRank: number
  era: string
}

export interface OverviewCell {
  category: string
  eraRank: number
  era: string
  count: number
  topNodes: NodeBrief[]
}

export interface Overview {
  categories: string[]
  eras: EraBrief[]
  cells: OverviewCell[]
  totalNodes: number
  totalEdges: number
}

export interface Neighborhood {
  center: NodeBrief
  nodes: NodeBrief[]
  edges: EdgeBrief[]
}

export interface SubgraphFilters {
  category?: string | null
  eraRank?: number | null
  q?: string | null
  minImportance?: number | null
  limit?: number
}

export interface SourceBrief {
  title: string
  url: string
  source?: string | null
  updatedAt: string
}
