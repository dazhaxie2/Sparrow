/**
 * 跨模块共享的图谱节点基础契约。
 *
 * 这两个类型是业务模块间流转的最小公共子集(例如 AI 对话需要引用图谱节点),
 * 放在 shared 以避免业务模块互相 import。graph 模块的完整领域模型(含
 * NodeDetail / Tree / Overview 等)仍留在 modules/graph/types,并通过
 * re-export 引用这里的定义。
 */
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
  /** 服务端预计算布局坐标;普通详情/搜索响应可不提供。 */
  x?: number
  y?: number
  /** LOD 瓦片元数据,用于从远景代表点下钻。 */
  clusterId?: number
  lodLevel?: number
  /** 聚簇代表点覆盖的真实节点数;普通节点为空。 */
  clusterSize?: number
}

export interface EdgeBrief {
  from: number
  to: number
  /** 关系类型:0=依赖/前置(默认),1=结构/分类归属。 */
  relation?: number
  label?: string | null
}
