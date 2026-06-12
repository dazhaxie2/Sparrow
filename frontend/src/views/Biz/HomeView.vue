<template>
  <AppHeader @open-login="showLogin = true" @open-member="showMemberModal" />
  <main class="layout">
    <div ref="chartRef" class="chart-area"></div>
    <GraphPanel :detail="selectedDetail" @select="showNode" @open-member="showMemberModal" />
  </main>
  <AiDock />
  <LoginModal v-if="showLogin" @close="showLogin = false" />
  <MemberModal v-if="showMember" @close="showMember = false" />
</template>

<script setup lang="ts">
import { ref, onMounted, onUnmounted } from 'vue'
import * as echarts from 'echarts'
import AppHeader from '../../components/common/AppHeader.vue'
import GraphPanel from '../../components/common/GraphPanel.vue'
import AiDock from '../../components/ai/AiDock.vue'
import LoginModal from '../../components/common/LoginModal.vue'
import MemberModal from '../../components/common/MemberModal.vue'
import { fetchTree, fetchNode, fetchPrerequisites } from '../../api/biz/graph'
import type { Tree, NodeBrief, NodeDetail } from '../../types/biz'
import { useUserStore } from '../../store/biz/user'

const ERA_COLORS: Record<number, string> = {
  1: '#8d6e63', 2: '#7cb342', 3: '#c08a2d', 4: '#26a69a', 5: '#5c6bc0',
  6: '#ab47bc', 7: '#ef6c00', 8: '#f9a825', 9: '#29b6f6', 10: '#ec407a',
}
const COL_W = 230
const ROW_H = 66

const user = useUserStore()
const chartRef = ref<HTMLElement | null>(null)
let chart: echarts.ECharts | null = null
const treeData = ref<Tree | null>(null)
const highlight = ref<{ selectedId: number; chainIds: Set<number> } | null>(null)
const selectedDetail = ref<NodeDetail | null>(null)
const showLogin = ref(false)
const showMember = ref(false)

function showMemberModal() {
  if (!user.isLoggedIn()) { showLogin.value = true; return }
  showMember.value = true
}

function layoutNodes(nodes: NodeBrief[]) {
  const byEra: Record<number, NodeBrief[]> = {}
  for (const n of nodes) (byEra[n.eraRank] = byEra[n.eraRank] || []).push(n)
  const pos: Record<number, { x: number; y: number }> = {}
  for (const [rank, list] of Object.entries(byEra)) {
    const r = Number(rank)
    for (let i = 0; i < list.length; i++)
      pos[list[i].id] = { x: (r - 1) * COL_W, y: (i - (list.length - 1) / 2) * ROW_H }
  }
  return pos
}

function buildOption() {
  if (!treeData.value) return {}
  const nodes = treeData.value.nodes
  const edges = treeData.value.edges
  const pos = layoutNodes(nodes)
  const chain = highlight.value ? highlight.value.chainIds : null
  const selected = highlight.value ? highlight.value.selectedId : null
  const eras: Record<number, string> = {}
  for (const n of nodes) eras[n.eraRank] = n.era
  const eraLabels = Object.entries(eras).map(([rank, eraName]) => {
    const r = Number(rank)
    return {
      id: `era-${rank}`, name: eraName, x: (r - 1) * COL_W,
      y: Math.min(...nodes.filter(n => n.eraRank === r).map(n => pos[n.id].y)) - 70,
      symbol: 'rect' as const, symbolSize: [1, 1],
      itemStyle: { color: 'transparent' },
      label: { show: true, color: ERA_COLORS[r], fontSize: 15, fontWeight: 700, formatter: eraName },
      tooltip: { show: false },
    }
  })
  const data = nodes.map(n => {
    const inChain = chain && (chain.has(n.id) || n.id === selected)
    const dim = chain && !inChain
    return {
      id: String(n.id), name: n.name, x: pos[n.id].x, y: pos[n.id].y,
      symbol: 'roundRect', symbolSize: [Math.max(n.name.length * 13 + 18, 56), 30],
      itemStyle: {
        color: ERA_COLORS[n.eraRank], opacity: dim ? 0.16 : 1,
        borderColor: n.id === selected ? '#ffffff' : inChain ? '#f6c244' : 'rgba(0,0,0,0)',
        borderWidth: n.id === selected ? 2.5 : inChain ? 2 : 0,
        shadowBlur: inChain ? 12 : 0, shadowColor: 'rgba(246,194,68,.6)',
      },
      label: {
        show: true, color: '#0d1421', fontSize: 12, fontWeight: 600,
        formatter: (n.premium ? '👑' : '') + n.name, opacity: dim ? 0.25 : 1,
      },
      _nodeId: n.id,
    }
  }).concat(eraLabels.map(e => ({ ...e, _nodeId: null as number | null })))
  const edgeData = edges.map(e => {
    const active = chain && (chain.has(e.from) || e.from === selected) && (chain.has(e.to) || e.to === selected)
    return {
      source: String(e.from), target: String(e.to),
      lineStyle: {
        color: active ? '#f6c244' : '#33415e', width: active ? 2 : 1,
        opacity: chain ? (active ? 0.95 : 0.12) : 0.55, curveness: 0.18,
      },
    }
  })
  return {
    backgroundColor: '#0d1421',
    tooltip: {
      confine: true, backgroundColor: '#1d2a44', borderColor: '#243049',
      textStyle: { color: '#e6edf7', width: 260, overflow: 'break' as const },
      extraCssText: 'max-width:300px;white-space:normal;',
    },
    series: [{
      type: 'graph', layout: 'none', roam: true, zoom: 0.55, center: [4.5 * COL_W, 0],
      data, edges: edgeData, edgeSymbol: ['none', 'arrow'], edgeSymbolSize: 7,
      emphasis: { disabled: true }, silent: false,
    }],
  }
}

function renderTree() { chart?.setOption(buildOption(), true) }

async function loadTree() { treeData.value = await fetchTree(); renderTree() }

async function showNode(id: number) {
  if (!treeData.value) return
  const [detail, chain] = await Promise.all([fetchNode(id), fetchPrerequisites(id)])
  highlight.value = { selectedId: detail.id, chainIds: new Set(chain.map(n => n.id)) }
  selectedDetail.value = detail
  renderTree()
}

function handleResize() { chart?.resize() }
function handleFocus() { user.loadProfile() }

onMounted(async () => {
  if (chartRef.value) {
    chart = echarts.init(chartRef.value)
    chart.on('click', (params: any) => {
      if (params.dataType === 'node' && params.data?._nodeId) showNode(params.data._nodeId)
    })
    window.addEventListener('resize', handleResize)
    window.addEventListener('focus', handleFocus)
  }
  await user.loadProfile()
  await loadTree()
})

onUnmounted(() => {
  window.removeEventListener('resize', handleResize)
  window.removeEventListener('focus', handleFocus)
  chart?.dispose()
})
</script>

<style scoped>
.layout { display: flex; height: calc(100vh - 56px); }
.chart-area { flex: 1; min-width: 0; }
</style>
