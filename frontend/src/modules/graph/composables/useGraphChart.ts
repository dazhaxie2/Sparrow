import { ref } from 'vue'
import * as echarts from 'echarts'
import type { Tree } from '../types'
import { buildOption, clamp, layoutNodes, type RenderContext } from './graphOption'
import { DEFAULT_VIEW } from '../constants'

type RenderState = Omit<RenderContext, 'currentView'>

/** ECharts 图谱交互：持有二维视图、定位与节点点击，不直接依赖页面状态。 */
export function useGraphChart(opts: {
  getTree: () => Tree | null
  getRenderState: () => RenderState
  onNodeClick: (id: number) => void
  isBusy: () => boolean
}) {
  let chart: echarts.ECharts | null = null
  let resizeObserver: ResizeObserver | null = null
  const currentView = ref({ ...DEFAULT_VIEW })

  function renderTree() {
    if (!chart) return
    const tree = opts.getTree()
    if (!tree) return
    chart.setOption(buildOption(tree, {
      ...opts.getRenderState(),
      currentView: currentView.value,
    }) as any, true)
  }

  function updateCurrentViewFromChart() {
    const option = chart?.getOption() as any
    const series = option?.series?.[0]
    if (!series) return
    const zoom = Array.isArray(series.zoom) ? series.zoom[0] : series.zoom
    const center = Array.isArray(series.center?.[0]) ? series.center[0] : series.center
    if (typeof zoom === 'number' && Array.isArray(center) && center.length >= 2) {
      currentView.value = { zoom, center: [Number(center[0]), Number(center[1])] }
    }
  }

  function layoutCenter(points: Array<{ x: number; y: number }>) {
    if (!points.length) return [0, 0] as [number, number]
    const xs = points.map(point => point.x)
    const ys = points.map(point => point.y)
    return [
      (Math.min(...xs) + Math.max(...xs)) / 2,
      (Math.min(...ys) + Math.max(...ys)) / 2,
    ] as [number, number]
  }

  function fitZoom(points: Array<{ x: number; y: number }>) {
    if (!chart || !points.length) return 0.88
    return clamp((chart.getWidth() / 1440) * 0.92, 0.42, 0.92)
  }

  /** 数据变更后回到完整图谱视图。 */
  function fitView() {
    const tree = opts.getTree()
    if (!chart || !tree?.nodes.length) return
    const positions = layoutNodes(tree.nodes, tree.edges)
    const points = Object.values(positions)
    currentView.value = { zoom: fitZoom(points), center: layoutCenter(points) }
    renderTree()
  }

  function focusCategory(category: string) {
    const tree = opts.getTree()
    if (!tree) return
    const positions = layoutNodes(tree.nodes, tree.edges)
    const points = tree.nodes
      .filter(node => (node.category?.trim() || '未分类') === category)
      .map(node => positions[node.id])
      .filter((point): point is NonNullable<typeof point> => Boolean(point))
    if (!points.length) return
    currentView.value = { zoom: 1.35, center: layoutCenter(points) }
    renderTree()
  }

  function centerNode(id: number, zoom = 1.55) {
    const tree = opts.getTree()
    if (!tree) return
    const point = layoutNodes(tree.nodes, tree.edges)[id]
    if (!point) return
    currentView.value = { zoom, center: [point.x, point.y] }
    renderTree()
  }

  function resetGraphView() {
    fitView()
  }

  function init(el: HTMLElement) {
    chart = echarts.init(el, undefined, { renderer: 'canvas' })
    resizeObserver = new ResizeObserver(() => chart?.resize())
    resizeObserver.observe(el)
    currentView.value = { ...DEFAULT_VIEW }
    chart.on('click', (params: any) => {
      if (opts.isBusy()) return
      if (params.dataType === 'node' && params.data?._nodeId) {
        opts.onNodeClick(params.data._nodeId)
      }
    })
    chart.on('graphRoam', updateCurrentViewFromChart)
  }

  function resize() {
    chart?.resize()
  }

  function dispose() {
    resizeObserver?.disconnect()
    resizeObserver = null
    chart?.dispose()
    chart = null
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
