/** 产业链专题类型(与科技图图谱模块完全独立)。 */

export interface ChainSummary {
  id: number
  slug: string
  name: string
  description: string | null
  coverColor: string | null
  nodeCount: number
}

export type ChainDetail = ChainSummary

export interface ChainNodeBrief {
  id: number
  name: string
  nodeType: string | null
  nodeTypeText: string
  summary: string | null
  importance: number
}

export interface ChainEdgeBrief {
  from: number
  to: number
  edgeType: string | null
  edgeTypeText: string
  product: string | null
}

export interface ChainGraph {
  nodes: ChainNodeBrief[]
  edges: ChainEdgeBrief[]
}

/** node_type → 主题色(与 graph 模块的全局色板对齐,便于复用 sigma 渲染)。 */
export const NODE_TYPE_COLORS: Record<string, string> = {
  核心公司: '#ff5722',
  供应商: '#1565c0',
  代工厂: '#2e7d32',
  材料商: '#8a8a8a',
}
