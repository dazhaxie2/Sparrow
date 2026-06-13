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
  sources: SourceBrief[]
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
