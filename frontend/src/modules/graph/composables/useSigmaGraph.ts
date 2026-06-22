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
  isBusy: () => boolean
}) {
  let sigma: Sigma | null = null
  let graph: Graph = null as unknown as Graph
  const currentView = ref({ ...DEFAULT_VIEW })

  // 视觉常量(与原 graphOption buildOption 对齐)
  const COLORS = {
    selected: '#ff5722',
    adjacent: '#e21b5a',
  }

  // ── 坐标系转换:layoutNodes 用像素坐标,sigma camera 用归一化 viewport 坐标 ──
  // currentView 接缝契约是 { zoom, center:[x,y] }(像素)。
  // sigma camera 是 { x, y, ratio }(ratio = 缩放倒数,ratio 小=放大)。
  // 过渡期:节点坐标直接喂 layoutNodes 的像素值,sigma 自动适配 viewport。
  // currentView 仅作状态镜像供外部读取,真正驱动靠 sigma camera。

  /** 节点大小(对齐 buildOption 的 baseSize 公式)。 */
  function nodeSize(node: NodeBrief, degree: number): number {
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
      const color = colorForCategory(node.category, legend)
      next.addNode(String(node.id), {
        x: point.x,
        y: point.y,
        size: nodeSize(node, point.degree),
        color,
        label: node.name,
        _nodeId: node.id,
        _category: node.category?.trim() || '未分类',
        _degree: point.degree,
        _importance: node.importance ?? 0,
        _premium: node.premium,
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
    sigma.setGraph(graph)
    applyReducers(ctx)
    sigma.refresh()
  }

  /** 根据 ctx(highlight/learning/dialog)挂 nodeReducer/edgeReducer 实现高亮。 */
  function applyReducers(ctx: RenderState) {
    if (!sigma) return
    const chain = ctx.highlight?.chainIds ?? null
    const selected = ctx.highlight?.selectedId ?? null
    const learningActive = ctx.learningActive
    const learningIds = learningActive ? new Set(ctx.learningNodes.map(n => n.id)) : null
    const learningCurrentId = ctx.learningCurrentId
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
      const inLearning = Boolean(learningIds?.has(id))
      const isLearningCurrent = id === learningCurrentId
      const isDialog = dialogIds.has(id)

      const sizeBoost = isLearningCurrent ? 6 : isSelected ? 5 : adjacent ? 2 : 0
      return {
        ...data,
        size: (data.size as number) + sizeBoost,
        color: isLearningCurrent || isSelected
          ? COLORS.selected
          : adjacent || inChain
            ? COLORS.adjacent
            : data.color as string,
        hidden: false,
        // 高亮:边框色 + 强制 label;暗化:降透明度(label 通过 forceLabel=false)
        forceLabel: isSelected || adjacent || inChain || inLearning || isDialog,
      } as Attributes
    })

    sigma.setSetting('edgeReducer', (_edgeId, data) => {
      return data
    })
  }

  // ── camera 同步 ──
  // sigma camera.animate 接受 viewport 归一化坐标;layoutNodes 输出像素坐标。
  // 过渡期用 sigma 自带的 viewportFromGraph / graphFromViewport 做转换不直接可用,
  // 改用 camera 的 animate 配合节点坐标做居中:中心点用节点平均坐标。
  function syncCurrentViewFromCamera() {
    if (!sigma) return
    const cam = sigma.getCamera()
    // ratio 越小越放大;zoom 用 1/ratio 近似
    const zoom = 1 / cam.ratio
    currentView.value = { zoom, center: [cam.x, cam.y] }
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
      .map(n => graph.getNodeAttributes(String(n.id)))
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
    const point = graph.getNodeAttributes(key)
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
    // 点击空白处取消选中交回 HomeView 处理(保持 ECharts 行为一致:不做隐式取消)
    sigma.on('clickStage', ({ event }) => {
      // sigma v3: 点击 stage 但未命中节点时 event.target 为空
      if (!(event as any).node) {
        // 不在此取消,HomeView 的 clearSelection 由二次点击同节点触发
      }
    })
    sigma.getCamera().on('updated', syncCurrentViewFromCamera)
  }

  function dispose() {
    sigma?.kill()
    sigma = null
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
