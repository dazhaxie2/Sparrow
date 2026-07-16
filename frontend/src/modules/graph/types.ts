export interface NodeBrief {
  id: number
  code: string
  name: string
  era: string
  eraRank: number
  yearLabel: string
  summary: string
  premium: boolean
  category?: string | null
  importance?: number | null
  /** 服务端预计算布局坐标；普通详情/搜索响应可不提供。 */
  x?: number
  y?: number
  /** LOD 瓦片元数据，用于从远景代表点下钻。 */
  clusterId?: number
  lodLevel?: number
  /** 聚簇代表点覆盖的真实节点数；普通节点为空。 */
  clusterSize?: number
}

export interface EdgeBrief {
  from: number
  to: number
  /** 关系类型:0=依赖/前置(默认),1=结构/分类归属。 */
  relation?: number
  label?: string | null
}

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
