/** 图谱模块共享常量。 */

/** 默认边关系名(无具体标签时回退)。 */
export const DEFAULT_EDGE_LABEL = '前置'

/** ECharts graph 默认视图(初次挂载用,随后 fitView 覆盖)。 */
export const DEFAULT_VIEW = { zoom: 0.88, center: [0, 0] as [number, number] }
