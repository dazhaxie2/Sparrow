import {
  forceCenter,
  forceCollide,
  forceLink,
  forceManyBody,
  forceSimulation,
  forceX,
  forceY,
  type SimulationLinkDatum,
  type SimulationNodeDatum,
} from 'd3-force'
import type { Tree, NodeBrief } from '../types'
import { DEFAULT_EDGE_LABEL } from '../constants'

export type GraphPoint = {
  x: number
  y: number
  degree: number
}

export type CategoryLegendItem = {
  name: string
  count: number
  color: string
}

export interface RenderContext {
  currentView: { zoom: number; center: [number, number] }
  highlight: { selectedId: number; chainIds: Set<number> } | null
  dialogActive: boolean
  dialogNodeIds: Set<number>
  showInlineEdgeLabels: boolean
  nodeById: Map<number, NodeBrief>
  categoryOrder: string[]
}

type ForceNode = SimulationNodeDatum & {
  id: number
  category: string
  degree: number
}

type ForceEdge = SimulationLinkDatum<ForceNode> & {
  source: number | ForceNode
  target: number | ForceNode
  pairTotal: number
}

const CATEGORY_PALETTE = [
  '#ff5b3a', '#0b5b88', '#706974',
  '#ff5b3a', '#0b5b88', '#706974',
  '#ff5b3a', '#0b5b88', '#706974',
  '#ff5b3a', '#0b5b88', '#706974',
  '#ff5b3a', '#0b5b88', '#706974',
]

export function clamp(value: number, min: number, max: number) {
  return Math.min(Math.max(value, min), max)
}

function categoryName(node: NodeBrief) {
  return node.category?.trim() || '未分类'
}

export function createCategoryLegend(nodes: NodeBrief[], categoryOrder: string[] = []): CategoryLegendItem[] {
  const counts = new Map<string, number>()
  for (const node of nodes) {
    const name = categoryName(node)
    counts.set(name, (counts.get(name) ?? 0) + 1)
  }
  const stableOrder = [...new Set([...categoryOrder, ...counts.keys()])]
  return [...counts.entries()]
    .sort((a, b) => b[1] - a[1] || a[0].localeCompare(b[0], 'zh-CN'))
    .map(([name, count]) => ({
      name,
      count,
      color: CATEGORY_PALETTE[Math.max(0, stableOrder.indexOf(name)) % CATEGORY_PALETTE.length],
    }))
}

export function colorForCategory(category: string | null | undefined, legend: CategoryLegendItem[]) {
  const name = category?.trim() || '未分类'
  return legend.find(item => item.name === name)?.color ?? '#52525b'
}

let layoutCache: { key: string; positions: Record<number, GraphPoint> } | null = null

function pairKey(from: number, to: number) {
  return from < to ? `${from}:${to}` : `${to}:${from}`
}

/**
 * D3 只负责离线计算稳定坐标，实际绘制仍交给 ECharts Canvas。
 * 类别弱聚类、拓扑强连接，避免按时代排队，也避免不同领域被彻底割裂。
 */
export function layoutNodes(nodes: NodeBrief[], edges: Tree['edges']): Record<number, GraphPoint> {
  if (!nodes.length) return {}
  const key = `${nodes.map(node => `${node.id}:${categoryName(node)}:${node.importance ?? 0}`).join('|')}::${edges.map(edge => `${edge.from}-${edge.to}`).join('|')}`
  if (layoutCache?.key === key) return layoutCache.positions

  const nodeIds = new Set(nodes.map(node => node.id))
  const validEdges = edges.filter(edge => nodeIds.has(edge.from) && nodeIds.has(edge.to))
  const degrees = new Map(nodes.map(node => [node.id, 0]))
  const pairTotals = new Map<string, number>()
  for (const edge of validEdges) {
    degrees.set(edge.from, (degrees.get(edge.from) ?? 0) + 1)
    degrees.set(edge.to, (degrees.get(edge.to) ?? 0) + 1)
    const key = pairKey(edge.from, edge.to)
    pairTotals.set(key, (pairTotals.get(key) ?? 0) + 1)
  }

  const legend = createCategoryLegend(nodes)
  const categoryRadius = clamp(90 + Math.sqrt(nodes.length) * 8, 130, 330)
  const anchors = new Map<string, { x: number; y: number }>()
  legend.forEach((item, index) => {
    const angle = (Math.PI * 2 * index) / Math.max(legend.length, 1) - Math.PI / 2
    const radius = legend.length <= 1 ? 0 : categoryRadius
    anchors.set(item.name, { x: Math.cos(angle) * radius, y: Math.sin(angle) * radius })
  })

  const forceNodes: ForceNode[] = nodes.map((node, index) => {
    const category = categoryName(node)
    const anchor = anchors.get(category) ?? { x: 0, y: 0 }
    const angle = index * Math.PI * (3 - Math.sqrt(5))
    const jitter = 20 + (index % 11) * 3
    return {
      id: node.id,
      category,
      degree: degrees.get(node.id) ?? 0,
      x: anchor.x + Math.cos(angle) * jitter,
      y: anchor.y + Math.sin(angle) * jitter,
    }
  })
  const forceEdges: ForceEdge[] = validEdges.map(edge => ({
    source: edge.from,
    target: edge.to,
    pairTotal: pairTotals.get(pairKey(edge.from, edge.to)) ?? 1,
  }))

  const charge = clamp(-110 - Math.sqrt(nodes.length) * 4, -250, -125)
  const simulation = forceSimulation(forceNodes)
    .randomSource(() => 0.5)
    .force('link', forceLink<ForceNode, ForceEdge>(forceEdges)
      .id(node => node.id)
      .distance(edge => 96 + Math.min(4, edge.pairTotal - 1) * 24)
      .strength(0.22))
    .force('charge', forceManyBody<ForceNode>().strength(charge).distanceMax(650))
    .force('collide', forceCollide<ForceNode>().radius(node => 18 + Math.min(9, Math.sqrt(node.degree) * 2)).iterations(2))
    .force('center', forceCenter(0, 0))
    .force('category-x', forceX<ForceNode>(node => anchors.get(node.category)?.x ?? 0).strength(0.055))
    .force('category-y', forceY<ForceNode>(node => anchors.get(node.category)?.y ?? 0).strength(0.055))
    .stop()

  const ticks = nodes.length > 600 ? 180 : nodes.length > 260 ? 240 : 320
  simulation.tick(ticks)

  const positions: Record<number, GraphPoint> = {}
  for (const node of forceNodes) {
    positions[node.id] = {
      x: Math.round((node.x ?? 0) * 1.9),
      y: Math.round((node.y ?? 0) * 1.04),
      degree: node.degree,
    }
  }
  layoutCache = { key, positions }
  return positions
}

/** 由图谱数据和交互状态构造 ECharts graph option。 */
export function buildOption(tree: Tree, ctx: RenderContext) {
  const nodes = tree.nodes
  const edges = tree.edges
  const positions = layoutNodes(nodes, edges)
  const legend = createCategoryLegend(nodes, ctx.categoryOrder)
  const chain = ctx.highlight?.chainIds ?? null
  const selected = ctx.highlight?.selectedId ?? null
  const adjacentIds = new Set<number>()
  if (selected != null) {
    for (const edge of edges) {
      if (edge.from === selected) adjacentIds.add(edge.to)
      if (edge.to === selected) adjacentIds.add(edge.from)
    }
  }

  const data = nodes.map(node => {
    const point = positions[node.id] ?? { x: 0, y: 0, degree: 0 }
    const inChain = Boolean(chain && (chain.has(node.id) || node.id === selected))
    const adjacent = adjacentIds.has(node.id)
    const isSelected = node.id === selected
    const isDialogNode = ctx.dialogNodeIds.has(node.id)
    const importance = node.importance ?? 0
    const labelAlways = isSelected || adjacent || inChain || isDialogNode
    const labelVisible = labelAlways
      || nodes.length <= 90
      || (nodes.length <= 420 && (point.degree >= 3 || importance >= 55))
      || (nodes.length <= 900 && (point.degree >= 5 || importance >= 85))
    const color = colorForCategory(node.category, legend)
    const densityScale = nodes.length > 600 ? 0.72 : nodes.length > 350 ? 0.86 : 1
    const maxNodeSize = nodes.length > 600 ? 11 : nodes.length > 350 ? 13 : 15
    const baseSize = clamp((5 + Math.sqrt(point.degree) * 1.25 + importance * 0.024) * densityScale, 5, maxNodeSize)
    const symbolSize = baseSize + (isSelected ? 5 : adjacent ? 2 : 0)
    const shortName = node.name.length > 12 ? `${node.name.slice(0, 12)}…` : node.name

    return {
      id: String(node.id),
      name: node.name,
      x: point.x,
      y: point.y,
      symbol: node.premium ? 'diamond' : 'circle',
      symbolSize,
      draggable: true,
      itemStyle: {
        color,
        opacity: 0.96,
        borderColor: isSelected ? '#ff5722' : adjacent || inChain ? '#e21b5a' : '#ffffff',
        borderWidth: isSelected ? 2.4 : adjacent || inChain ? 1.8 : nodes.length > 600 ? 0.7 : 1,
        shadowBlur: isSelected ? 14 : adjacent || inChain || isDialogNode ? 8 : 0,
        shadowColor: 'rgba(255, 87, 34, 0.28)',
      },
      label: {
        show: labelVisible,
        color: '#30343a',
        fontSize: labelAlways ? 10 : nodes.length > 420 ? 7 : 8,
        fontWeight: isSelected ? 800 : 600,
        formatter: `${node.premium ? 'PRO · ' : ''}${shortName}`,
        position: 'right',
        distance: 5,
        backgroundColor: labelAlways ? 'rgba(255,255,255,0.94)' : 'rgba(255,255,255,0.72)',
        borderColor: labelAlways ? 'rgba(229,229,229,0.95)' : 'transparent',
        borderWidth: labelAlways ? 1 : 0,
        borderRadius: 3,
        padding: labelAlways ? [3, 5] : [1, 2],
      },
      _nodeId: node.id,
    }
  })

  const pairIndexes = new Map<string, number>()
  const pairTotals = new Map<string, number>()
  for (const edge of edges) {
    const key = pairKey(edge.from, edge.to)
    pairTotals.set(key, (pairTotals.get(key) ?? 0) + 1)
  }
  const edgeData = edges.map(edge => {
    const key = pairKey(edge.from, edge.to)
    const total = pairTotals.get(key) ?? 1
    const index = pairIndexes.get(key) ?? 0
    pairIndexes.set(key, index + 1)
    const sourceActive = edge.from === selected || edge.to === selected
    const chainActive = Boolean(chain && (chain.has(edge.from) || edge.from === selected) && (chain.has(edge.to) || edge.to === selected))
    const active = sourceActive || chainActive
    const edgeLabel = edge.label?.trim() || DEFAULT_EDGE_LABEL
    const sourceName = ctx.nodeById.get(edge.from)?.name ?? String(edge.from)
    const targetName = ctx.nodeById.get(edge.to)?.name ?? String(edge.to)
    const centeredIndex = index - (total - 1) / 2
    const direction = edge.from <= edge.to ? 1 : -1
    const curveness = edge.from === edge.to ? 0.45 : total > 1 ? centeredIndex * 0.18 * direction : 0

    return {
      source: String(edge.from),
      target: String(edge.to),
      edgeLabel,
      relationText: `${sourceName} → ${targetName}`,
      label: {
        show: ctx.showInlineEdgeLabels && edgeLabel !== DEFAULT_EDGE_LABEL && (active || nodes.length <= 220),
        formatter: edgeLabel,
        position: 'middle',
        color: active ? '#e54b20' : '#626a73',
        fontSize: 9,
        backgroundColor: 'rgba(255,255,255,0.96)',
        borderColor: 'rgba(226,226,226,0.9)',
        borderWidth: 1,
        borderRadius: 3,
        padding: [2, 5],
      },
      lineStyle: {
        color: active ? '#ff5722' : ctx.dialogActive ? '#9ca8b4' : '#b5bdc6',
        width: active ? 1.8 : ctx.dialogActive ? 0.8 : 0.65,
        opacity: active ? 0.96 : ctx.dialogActive ? 0.44 : 0.38,
        curveness,
      },
    }
  })

  return {
    backgroundColor: 'transparent',
    textStyle: { fontFamily: 'JetBrains Mono, Space Grotesk, Noto Sans SC, Microsoft YaHei, sans-serif' },
    tooltip: {
      confine: true,
      backgroundColor: '#ffffff',
      borderColor: '#d8d8d8',
      borderWidth: 1,
      textStyle: { color: '#111111', width: 270, overflow: 'break' as const },
      extraCssText: 'max-width:310px;white-space:normal;box-shadow:0 12px 28px rgba(0,0,0,.12);border-radius:6px;',
      formatter: (params: any) => {
        if (params.dataType === 'edge') {
          return `<strong>${params.data?.relationText || ''}</strong><br/>关系：${params.data?.edgeLabel || DEFAULT_EDGE_LABEL}`
        }
        const node = ctx.nodeById.get(params.data?._nodeId)
        if (!node) return params.name
        return `<strong>${node.name}</strong><br/>${categoryName(node)}<br/>${node.summary || '暂无摘要'}`
      },
    },
    series: [{
      type: 'graph',
      layout: 'none',
      roam: true,
      scaleLimit: { min: 0.16, max: 5 },
      zoom: ctx.currentView.zoom,
      center: ctx.currentView.center,
      data,
      edges: edgeData,
      edgeSymbol: ['none', 'arrow'],
      edgeSymbolSize: [0, 5],
      labelLayout: { hideOverlap: nodes.length > 130, moveOverlap: 'shiftY' },
      animation: nodes.length <= 220,
      animationDurationUpdate: nodes.length > 220 ? 0 : 220,
      animationEasingUpdate: 'cubicOut',
      emphasis: {
        focus: 'none',
        label: { show: true, backgroundColor: 'rgba(255,255,255,0.96)' },
        itemStyle: { shadowBlur: 20, shadowColor: 'rgba(255,87,34,0.35)' },
        lineStyle: { width: 2.2, opacity: 0.95 },
      },
    }],
  }
}
