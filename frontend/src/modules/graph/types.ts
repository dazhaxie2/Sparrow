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
}

export interface EdgeBrief {
  from: number
  to: number
  label?: string | null
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
  sources: SourceBrief[]
  category?: string | null
  importance?: number | null
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

export interface NodePage {
  nodes: NodeBrief[]
  total: number
  page: number
  size: number
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
  updatedAt: string
}

export interface KnowledgeStatus {
  ragDocumentCount: number
  ragUpdatedAt: string | null
  ragIndexed: boolean
  ragNodeCount: number | null
  ragChunkCount: number | null
  ragIndexUpdatedAt: string | null
}
