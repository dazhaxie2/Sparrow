import { ref } from 'vue'
import { useSigmaGraph } from '../../graph/composables/useSigmaGraph'
import type { EdgeBrief, NodeBrief, Tree } from '../../graph/types'
import { NODE_TYPE_COLORS } from '../types'
import type { ChainEdgeBrief, ChainGraph, ChainNodeBrief } from '../types'

/**
 * 供应链网络图渲染:把产业链数据适配成 graph 模块的 NodeBrief/EdgeBrief/Tree 形状,
 * 复用 useSigmaGraph(sigma.js + 浏览器端 d3-force 布局)。供应链规模小(几十节点),
 * 无需服务端坐标,直接走 force 布局即可。
 *
 * 复用要点:
 * - nodeType → category(决定分色,useSigmaGraph 用 colorForCategory 着色)
 * - importance → importance(决定节点大小)
 * - edgeType → label(连线上显示"供货/代工/材料供应/授权")
 */
export function useChainGraph() {
  const tree = { nodes: [] as NodeBrief[], edges: [] as EdgeBrief[] }
  const selectedNodeId = ref<number | null>(null)
  const selectedNeighbors = new Set<number>()
  let rerender = () => {}

  function setGraph(graph: ChainGraph) {
    tree.nodes = graph.nodes.map((n) => toNodeBrief(n))
    tree.edges = graph.edges.map((e) => toEdgeBrief(e))
    selectedNodeId.value = null
    selectedNeighbors.clear()
  }

  function toNodeBrief(n: ChainNodeBrief): NodeBrief {
    return {
      id: n.id,
      code: `chain_${n.id}`,
      name: n.name,
      era: '',
      eraRank: 0,
      yearLabel: '',
      summary: n.summary || '',
      premium: false,
      // nodeType 映射为 category,使 useSigmaGraph 的分色逻辑生效。
      category: n.nodeType || '公司',
      importance: n.importance ?? 0,
    }
  }

  function toEdgeBrief(e: ChainEdgeBrief): EdgeBrief {
    return { from: e.from, to: e.to, label: e.edgeTypeText }
  }

  function getTree(): Tree | null {
    return tree.nodes.length ? tree : null
  }

  const chart = useSigmaGraph({
    getTree,
    getRenderState: () => ({
      highlight: selectedNodeId.value != null
        ? { selectedId: selectedNodeId.value, chainIds: selectedNeighbors }
        : null,
      dialogActive: false,
      dialogNodeIds: new Set<number>(),
      showInlineEdgeLabels: false,
      nodeById: new Map(tree.nodes.map((n) => [n.id, n])),
      categoryOrder: ['核心公司', '供应商', '代工厂', '材料商', '公司'],
    }),
    getNodeColor: (node) => NODE_TYPE_COLORS[node.category || ''] || '#8a8a8a',
    onNodeClick: (id: number) => {
      if (selectedNodeId.value === id) {
        selectedNodeId.value = null
        selectedNeighbors.clear()
      } else {
        selectedNodeId.value = id
        selectedNeighbors.clear()
        for (const edge of tree.edges) {
          if (edge.from === id) selectedNeighbors.add(edge.to)
          if (edge.to === id) selectedNeighbors.add(edge.from)
        }
      }
      rerender()
    },
    isBusy: () => false,
  })

  rerender = chart.renderTree

  return { chart, selectedNodeId, setGraph }
}
