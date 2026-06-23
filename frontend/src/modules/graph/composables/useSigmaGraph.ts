import { ref } from 'vue'
import Graph from 'graphology'
import Sigma from 'sigma'
import type { Attributes } from 'graphology-types'
import type { Tree, NodeBrief } from '../types'
import {
  createCategoryLegend,
  clamp,
  layoutNodes,
  colorForCategory,
  type RenderContext,
} from './graphOption'
import { DEFAULT_VIEW } from '../constants'

type RenderState = Omit<RenderContext, 'currentView'>

/**
 * sigma.js (WebGL) 图谱交互,接缝与 useGraphChart 完全一致:
 * init / renderTree / fitView / centerNode / focusCategory / resize / dispose / resetGraphView + currentView。
 * 这样 HomeView.vue 仅改一行 import 即可切换渲染栈。
 *
 * 百万级路线:M1 过渡期复用 layoutNodes(d3-force 离线坐标,撑万级);
 * M2 后由服务端 SFDP 坐标 + 视口瓦片驱动,layoutNodes 退役。
 */
export function useSigmaGraph(opts: {
  getTree: () => Tree | null
  getRenderState: () => RenderState
  onNodeClick: (id: number) => void
  /** 可选的模块级节点配色；未提供时沿用科技图谱的类别色板。 */
  getNodeColor?: (node: NodeBrief) => string | null | undefined
  onViewChange?: (view: { zoom: number; centerClusterId: number | null }) => void
  isBusy: () => boolean
}) {
  let sigma: Sigma | null = null
  let graph: Graph = null as unknown as Graph
  let hoveredNodeId: number | null = null
  let viewChangeTimer: ReturnType<typeof setTimeout> | null = null
  const currentView = ref({ ...DEFAULT_VIEW })

  // 视觉常量(与原 graphOption buildOption 对齐)
  const COLORS = {
    selected: '#ff5722',
    adjacent: '#e21b5a',
    dimmedNode: '#d9dee2',
    dimmedEdge: '#e4e8eb',
    hoveredEdge: '#98a4ae',
  }

  // ── 坐标系转换:layoutNodes 用像素坐标,sigma camera 用归一化 viewport 坐标 ──
  // currentView 接缝契约是 { zoom, center:[x,y] }(像素)。
  // sigma camera 是 { x, y, ratio }(ratio = 缩放倒数,ratio 小=放大)。
  // 过渡期:节点坐标直接喂 layoutNodes 的像素值,sigma 自动适配 viewport。
  // currentView 仅作状态镜像供外部读取,真正驱动靠 sigma camera。

  /** 节点大小(对齐 buildOption 的 baseSize 公式)。 */
  function nodeSize(node: NodeBrief, degree: number): number {
    if ((node.clusterSize ?? 0) > 1) {
      return clamp(9 + Math.sqrt(node.clusterSize ?? 1) * 0.65, 12, 36)
    }
    const n = opts.getTree()?.nodes.length ?? 0
    const densityScale = n > 600 ? 0.72 : n > 350 ? 0.86 : 1
    const maxNodeSize = n > 600 ? 11 : n > 350 ? 13 : 15
    const importance = node.importance ?? 0
    const base = clamp((5 + Math.sqrt(degree) * 1.25 + importance * 0.024) * densityScale, 5, maxNodeSize)
    return base
  }

  /** 优先使用服务端预计算坐标；只有新增的详情/搜索节点才回退到浏览器端布局。 */
  function positionsFor(tree: Tree) {
    const degree = new Map<number, number>()
    for (const edge of tree.edges) {
      degree.set(edge.from, (degree.get(edge.from) ?? 0) + 1)
      degree.set(edge.to, (degree.get(edge.to) ?? 0) + 1)
    }
    const needsFallback = tree.nodes.some(node => !Number.isFinite(node.x) || !Number.isFinite(node.y))
    const fallback: Record<number, { x: number; y: number; degree: number }> = needsFallback
      ? layoutNodes(tree.nodes, tree.edges)
      : {}
    return Object.fromEntries(tree.nodes.map(node => {
      const point = fallback[node.id] ?? { x: 0, y: 0, degree: degree.get(node.id) ?? 0 }
      return [node.id, {
        x: Number.isFinite(node.x) ? node.x as number : point.x,
        y: Number.isFinite(node.y) ? node.y as number : point.y,
        degree: degree.get(node.id) ?? point.degree,
      }]
    }))
  }

  /** 从当前 tree + 渲染状态重建 graphology 实例并刷新 sigma。 */
  function renderTree() {
    if (!sigma) return
    const tree = opts.getTree()
    if (!tree) return

    const ctx = opts.getRenderState()
    const positions = positionsFor(tree)
    const legend = createCategoryLegend(tree.nodes, ctx.categoryOrder)

    // 重建 graph(全量替换:M1 简化策略;M2 瓦片化后改增量合并)
    const next = new Graph({ multi: false, type: 'undirected' })
    for (const node of tree.nodes) {
      const point = positions[node.id] ?? { x: 0, y: 0, degree: 0 }
      const color = opts.getNodeColor?.(node) || colorForCategory(node.category, legend)
      const fullLabel = (node.clusterSize ?? 0) > 1
        ? `${node.name} · ${node.clusterSize} 节点`
        : node.name
      const showBaseLabel = (node.clusterSize ?? 0) > 1
        || tree.nodes.length <= 120
        || point.degree >= 3
        || (node.importance ?? 0) >= 85
      next.addNode(String(node.id), {
        x: point.x,
        y: point.y,
        size: nodeSize(node, point.degree),
        color,
        label: showBaseLabel ? fullLabel : null,
        _nodeId: node.id,
        _category: node.category?.trim() || '未分类',
        _degree: point.degree,
        _importance: node.importance ?? 0,
        _premium: node.premium,
        _clusterId: node.clusterId ?? null,
        _lodLevel: node.lodLevel ?? null,
        _clusterSize: node.clusterSize ?? 0,
        _fullLabel: fullLabel,
      } as Attributes)
    }
    for (const edge of tree.edges) {
      const sk = String(edge.from)
      const tk = String(edge.to)
      if (!next.hasNode(sk) || !next.hasNode(tk)) continue
      // graphology 边 key 唯一,重复 addEdge 会抛错,用 dropEdge 兜底
      if (next.hasEdge(sk, tk)) continue
      try {
        next.addEdge(sk, tk, { color: '#b5bdc6', size: 0.8 })
      } catch {
        /* 忽略自环/重复 */
      }
    }

    graph = next
    if (hoveredNodeId != null && !graph.hasNode(String(hoveredNodeId))) hoveredNodeId = null
    sigma.setGraph(graph)
    applyReducers(ctx)
    sigma.refresh()
  }

  /** 根据 ctx(highlight/dialog)挂 nodeReducer/edgeReducer 实现高亮。 */
  function applyReducers(ctx: RenderState) {
    if (!sigma) return
    const chain = ctx.highlight?.chainIds ?? null
    const selected = ctx.highlight?.selectedId ?? null
    const dialogIds = ctx.dialogNodeIds

    const adjacentIds = new Set<number>()
    if (selected != null) {
      const tree = opts.getTree()
      for (const edge of tree?.edges ?? []) {
        if (edge.from === selected) adjacentIds.add(edge.to)
        if (edge.to === selected) adjacentIds.add(edge.from)
      }
    }

    sigma.setSetting('nodeReducer', (nodeId, data) => {
      const id = data._nodeId as number
      const isSelected = id === selected
      const inChain = Boolean(chain && (chain.has(id) || id === selected))
      const adjacent = adjacentIds.has(id)
      const isDialog = dialogIds.has(id)
      const isHovered = id === hoveredNodeId
      const persistentlyEmphasized = isSelected || adjacent || inChain || isDialog
      const dimmed = selected != null && !persistentlyEmphasized && !isHovered

      const sizeBoost = isSelected ? 5 : adjacent ? 2 : 0
      return {
        ...data,
        size: ((data.size as number) + sizeBoost) * (dimmed ? 0.78 : 1),
        color: isSelected
          ? COLORS.selected
          : adjacent || inChain
            ? COLORS.adjacent
            : dimmed
              ? COLORS.dimmedNode
              : data.color as string,
        hidden: false,
        // highlighted 让持久强调节点继续绘制在 hover 图层，悬浮其他节点时不会被覆盖。
        highlighted: persistentlyEmphasized,
        forceLabel: persistentlyEmphasized || isHovered,
        label: persistentlyEmphasized || isHovered ? data._fullLabel : data.label,
      } as Attributes
    })

    sigma.setSetting('edgeReducer', (edgeId, data) => {
      const sourceId = Number(graph.source(edgeId))
      const targetId = Number(graph.target(edgeId))
      const selectedEdge = selected != null && (sourceId === selected || targetId === selected)
      const chainEdge = Boolean(chain
        && (chain.has(sourceId) || sourceId === selected)
        && (chain.has(targetId) || targetId === selected))
      const persistentEdge = selectedEdge || chainEdge
      const hoveredEdge = hoveredNodeId != null
        && (sourceId === hoveredNodeId || targetId === hoveredNodeId)
      const dimmed = selected != null && !persistentEdge && !hoveredEdge

      return {
        ...data,
        color: persistentEdge
          ? COLORS.selected
          : hoveredEdge
            ? COLORS.hoveredEdge
            : dimmed
              ? COLORS.dimmedEdge
              : data.color,
        size: persistentEdge ? 1.7 : hoveredEdge ? 1.15 : dimmed ? 0.45 : data.size,
      }
    })
  }

  // ── camera 同步 ──
  // sigma camera 使用归一化后的 framed-graph 坐标，而 graphology 保存原始布局坐标。
  // 聚焦时必须读取 Sigma 处理后的 display data，不能直接把原始 x/y 写入相机。
  function syncCurrentViewFromCamera() {
    if (!sigma) return
    const cam = sigma.getCamera()
    // ratio 越小越放大;zoom 用 1/ratio 近似
    const zoom = 1 / cam.ratio
    currentView.value = { zoom, center: [cam.x, cam.y] }
  }

  function scheduleViewChange() {
    if (!sigma || !opts.onViewChange) return
    if (viewChangeTimer) clearTimeout(viewChangeTimer)
    viewChangeTimer = setTimeout(() => {
      if (!sigma) return
      const camera = sigma.getCamera().getState()
      let centerClusterId: number | null = null
      let nearestDistance = Number.POSITIVE_INFINITY
      graph.forEachNode((key, data) => {
        if (Number(data._clusterSize ?? 0) <= 1 || data._clusterId == null) return
        const point = sigma?.getNodeDisplayData(key)
        if (!point) return
        const distance = (point.x - camera.x) ** 2 + (point.y - camera.y) ** 2
        if (distance < nearestDistance) {
          nearestDistance = distance
          centerClusterId = Number(data._clusterId)
        }
      })
      opts.onViewChange?.({ zoom: 1 / camera.ratio, centerClusterId })
    }, 180)
  }

  function fitView() {
    if (!sigma) return
    const tree = opts.getTree()
    if (!tree?.nodes.length) return
    sigma.getCamera().animatedReset()
    requestAnimationFrame(() => syncCurrentViewFromCamera())
  }

  function focusCategory(category: string) {
    if (!sigma) return
    const tree = opts.getTree()
    if (!tree) return
    const points = tree.nodes
      .filter(n => (n.category?.trim() || '未分类') === category)
      .filter(n => graph.hasNode(String(n.id)))
      .map(n => sigma?.getNodeDisplayData(String(n.id)))
      .filter((p): p is NonNullable<typeof p> => Boolean(p))
    if (!points.length) return
    const cx = (Math.min(...points.map(p => p.x)) + Math.max(...points.map(p => p.x))) / 2
    const cy = (Math.min(...points.map(p => p.y)) + Math.max(...points.map(p => p.y))) / 2
    // ratio ~0.74 对应 zoom ~1.35
    sigma.getCamera().animate({ x: cx, y: cy, ratio: 0.74 }, { duration: 400 })
    requestAnimationFrame(() => syncCurrentViewFromCamera())
  }

  function centerNode(id: number, zoom = 1.55) {
    if (!sigma) return
    const key = String(id)
    if (!graph.hasNode(key)) return
    const point = sigma.getNodeDisplayData(key)
    if (!point) return
    sigma.getCamera().animate({ x: point.x, y: point.y, ratio: 1 / zoom }, { duration: 400 })
    requestAnimationFrame(() => syncCurrentViewFromCamera())
  }

  function resetGraphView() {
    fitView()
  }

  function resize() {
    sigma?.refresh()
  }

  function init(el: HTMLElement) {
    graph = new Graph({ multi: false, type: 'undirected' })
    sigma = new Sigma(graph, el, {
      renderEdgeLabels: false,
      defaultEdgeColor: '#b5bdc6',
      defaultNodeColor: '#52525b',
      labelColor: { color: '#30343a' },
      labelDensity: 0.3,
      labelGridCellSize: 60,
      labelRenderedSizeThreshold: 6,
      labelFont: 'JetBrains Mono, Space Grotesk, Noto Sans SC, Microsoft YaHei, sans-serif',
      minCameraRatio: 0.05,
      maxCameraRatio: 20,
    })
    currentView.value = { ...DEFAULT_VIEW }
    sigma.on('clickNode', ({ node }) => {
      if (opts.isBusy()) return
      const data = graph.getNodeAttributes(node)
      if (data?._nodeId != null) opts.onNodeClick(data._nodeId as number)
    })
    sigma.on('enterNode', ({ node }) => {
      hoveredNodeId = graph.getNodeAttribute(node, '_nodeId') as number
      sigma?.refresh()
    })
    sigma.on('leaveNode', ({ node }) => {
      const leavingId = graph.getNodeAttribute(node, '_nodeId') as number
      if (hoveredNodeId === leavingId) hoveredNodeId = null
      sigma?.refresh()
    })
    // 点击空白处取消选中交回 HomeView 处理(保持 ECharts 行为一致:不做隐式取消)
    sigma.on('clickStage', ({ event }) => {
      // sigma v3: 点击 stage 但未命中节点时 event.target 为空
      if (!(event as any).node) {
        // 不在此取消,HomeView 的 clearSelection 由二次点击同节点触发
      }
    })
    sigma.getCamera().on('updated', () => {
      syncCurrentViewFromCamera()
      scheduleViewChange()
    })
  }

  function dispose() {
    sigma?.kill()
    sigma = null
    hoveredNodeId = null
    if (viewChangeTimer) clearTimeout(viewChangeTimer)
    viewChangeTimer = null
    graph = null as unknown as Graph
  }

  return {
    currentView,
    init,
    dispose,
    resize,
    renderTree,
    fitView,
    centerNode,
    focusCategory,
    resetGraphView,
  }
}
