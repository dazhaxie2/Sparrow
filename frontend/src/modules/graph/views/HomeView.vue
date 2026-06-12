<template>
  <AppHeader
    @open-login="showLogin = true"
    @open-member="showMemberModal"
    @focus-ai="openAiGuide"
  />
  <main class="layout">
    <section class="graph-workspace">
      <section class="intro-strip" aria-label="Technology tree overview">
        <div class="intro-copy">
          <div class="tag-row">
            <span class="accent-tag">SPARROW PHASE 1</span>
            <span>TECH DEPENDENCY MAP</span>
          </div>
          <h1>人类科技树</h1>
          <p>
            用依赖关系串联关键技术演进，从基础工具到智能系统，快速定位每个节点的前置知识、解锁路径与深度解读。
          </p>
        </div>

        <div class="metric-grid" aria-label="Graph metrics">
          <div class="metric">
            <span>{{ nodeCount }}</span>
            <strong>Nodes</strong>
          </div>
          <div class="metric">
            <span>{{ edgeCount }}</span>
            <strong>Relations</strong>
          </div>
          <div class="metric">
            <span>{{ eraCount }}</span>
            <strong>Eras</strong>
          </div>
          <div class="metric">
            <span>{{ premiumCount }}</span>
            <strong>Deep Dives</strong>
          </div>
        </div>

        <div class="workflow-card" aria-label="Workflow">
          <div class="workflow-title">Workflow</div>
          <div class="workflow-step"><span>01</span>Explore nodes</div>
          <div class="workflow-step"><span>02</span>Trace prerequisites</div>
          <div class="workflow-step"><span>03</span>Ask AI guide</div>
          <div class="workflow-step"><span>04</span>Unlock detail</div>
        </div>
      </section>

      <section class="graph-shell" :class="{ fullscreen: graphFullScreen }" aria-label="Technology graph">
        <div class="graph-toolbar">
          <div class="toolbar-title">
            <span class="panel-symbol">◆</span>
            <strong>TECHNOLOGY GRAPH</strong>
            <span>{{ nodeCount || '--' }} ITEMS</span>
          </div>
          <div class="toolbar-actions" aria-label="Graph controls">
            <span class="selected-status">{{ selectedDetail ? selectedDetail.name : 'Drag / Zoom / Click node' }}</span>
            <button class="tool-btn" type="button" title="刷新图谱" @click="refreshGraph">
              <span>↻</span>
              Refresh
            </button>
            <button class="tool-btn icon-only" type="button" title="重置视图" @click="resetGraphView">
              ◎
            </button>
            <button
              class="tool-btn icon-only"
              type="button"
              :title="graphFullScreen ? '退出全屏' : '全屏显示'"
              @click="toggleGraphFullScreen"
            >
              {{ graphFullScreen ? '↙' : '↗' }}
            </button>
            <span class="live-dot"></span>
          </div>
        </div>
        <div class="graph-canvas-wrap">
          <div ref="chartRef" class="chart-area"></div>
          <div v-if="eraLegend.length" class="era-legend">
            <span class="legend-title">Era Groups</span>
            <div class="legend-items">
              <button
                v-for="era in eraLegend"
                :key="era.rank"
                class="legend-item"
                type="button"
                @click="focusEra(era.rank)"
              >
                <span class="legend-dot" :style="{ background: era.color }"></span>
                <span>{{ era.name }}</span>
                <strong>{{ era.count }}</strong>
              </button>
            </div>
          </div>
        </div>
      </section>
    </section>

    <GraphPanel :detail="selectedDetail" @select="showNode" @open-member="showMemberModal" />
  </main>
  <AiDock ref="aiDockRef" />
  <LoginModal v-if="showLogin" @close="showLogin = false" />
  <MemberModal v-if="showMember" @close="showMember = false" />
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref } from 'vue'
import * as echarts from 'echarts'
import AppHeader from '../../../app/components/AppHeader.vue'
import GraphPanel from '../components/GraphPanel.vue'
import AiDock from '../../ai/components/AiDock.vue'
import LoginModal from '../../user/components/LoginModal.vue'
import MemberModal from '../../trade/components/MemberModal.vue'
import { fetchTree, fetchNode, fetchPrerequisites } from '../api'
import type { Tree, NodeBrief, NodeDetail } from '../types'
import { useUserStore } from '../../user/store'

const ERA_COLORS: Record<number, string> = {
  1: '#111111',
  2: '#4b5563',
  3: '#ff5722',
  4: '#0f766e',
  5: '#2563eb',
  6: '#7c3aed',
  7: '#ea580c',
  8: '#15803d',
  9: '#c2410c',
  10: '#111827',
}
const COL_W = 230
const ROW_H = 68

const user = useUserStore()
const chartRef = ref<HTMLElement | null>(null)
let chart: echarts.ECharts | null = null
const treeData = ref<Tree | null>(null)
const highlight = ref<{ selectedId: number; chainIds: Set<number> } | null>(null)
const selectedDetail = ref<NodeDetail | null>(null)
const showLogin = ref(false)
const showMember = ref(false)
const graphFullScreen = ref(false)
const aiDockRef = ref<InstanceType<typeof AiDock> | null>(null)

const nodeCount = computed(() => treeData.value?.nodes.length ?? 0)
const edgeCount = computed(() => treeData.value?.edges.length ?? 0)
const eraCount = computed(() => new Set(treeData.value?.nodes.map(node => node.eraRank) ?? []).size)
const premiumCount = computed(() => treeData.value?.nodes.filter(node => node.premium).length ?? 0)
const eraLegend = computed(() => {
  const map = new Map<number, { rank: number; name: string; count: number; color: string }>()
  for (const node of treeData.value?.nodes ?? []) {
    const current = map.get(node.eraRank)
    if (current) {
      current.count += 1
    } else {
      map.set(node.eraRank, {
        rank: node.eraRank,
        name: node.era,
        count: 1,
        color: ERA_COLORS[node.eraRank] || '#111111',
      })
    }
  }
  return [...map.values()].sort((a, b) => a.rank - b.rank)
})

function openAiGuide() {
  aiDockRef.value?.open()
}

function showMemberModal() {
  if (!user.isLoggedIn()) {
    showLogin.value = true
    return
  }
  showMember.value = true
}

function layoutNodes(nodes: NodeBrief[]) {
  const byEra: Record<number, NodeBrief[]> = {}
  for (const node of nodes) (byEra[node.eraRank] = byEra[node.eraRank] || []).push(node)
  const pos: Record<number, { x: number; y: number }> = {}
  for (const [rank, list] of Object.entries(byEra)) {
    const eraRank = Number(rank)
    for (let i = 0; i < list.length; i++) {
      pos[list[i].id] = {
        x: (eraRank - 1) * COL_W,
        y: (i - (list.length - 1) / 2) * ROW_H,
      }
    }
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
  for (const node of nodes) eras[node.eraRank] = node.era

  const eraLabels: any[] = Object.entries(eras).map(([rank, eraName]) => {
    const eraRank = Number(rank)
    const eraNodes = nodes.filter(node => node.eraRank === eraRank)
    const minY = eraNodes.length ? Math.min(...eraNodes.map(node => pos[node.id].y)) : 0
    return {
      id: `era-${rank}`,
      name: eraName,
      x: (eraRank - 1) * COL_W,
      y: minY - 72,
      symbol: 'rect',
      symbolSize: [1, 1],
      itemStyle: { color: 'transparent' },
      label: {
        show: true,
        color: ERA_COLORS[eraRank] || '#111111',
        fontSize: 14,
        fontWeight: 800,
        formatter: eraName,
      },
      tooltip: { show: false },
      _nodeId: null,
    }
  })

  const data: any[] = [
    ...nodes.map(node => {
      const inChain = chain && (chain.has(node.id) || node.id === selected)
      const dim = chain && !inChain
      const isSelected = node.id === selected
      return {
        id: String(node.id),
        name: node.name,
        x: pos[node.id].x,
        y: pos[node.id].y,
        symbol: 'roundRect',
        symbolSize: [Math.max(node.name.length * 14 + 30, 74), 32],
        itemStyle: {
          color: ERA_COLORS[node.eraRank] || '#111111',
          opacity: dim ? 0.18 : 1,
          borderColor: isSelected ? '#000000' : inChain ? '#ff5722' : '#ffffff',
          borderWidth: isSelected ? 3 : inChain ? 2 : 1,
          shadowBlur: inChain ? 14 : 0,
          shadowColor: 'rgba(255, 87, 34, 0.34)',
        },
        label: {
          show: true,
          color: '#ffffff',
          fontSize: 12,
          fontWeight: 700,
          formatter: `${node.premium ? '◆ ' : ''}${node.name}`,
          opacity: dim ? 0.28 : 1,
        },
        _nodeId: node.id,
      }
    }),
    ...eraLabels,
  ]

  const edgeData = edges.map(edge => {
    const active = chain && (chain.has(edge.from) || edge.from === selected) && (chain.has(edge.to) || edge.to === selected)
    return {
      source: String(edge.from),
      target: String(edge.to),
      lineStyle: {
        color: active ? '#ff5722' : '#d5d5d5',
        width: active ? 2.2 : 1,
        opacity: chain ? (active ? 0.96 : 0.16) : 0.62,
        curveness: 0.18,
      },
    }
  })

  return {
    backgroundColor: 'transparent',
    textStyle: {
      fontFamily: 'JetBrains Mono, Space Grotesk, Noto Sans SC, Microsoft YaHei, sans-serif',
    },
    tooltip: {
      confine: true,
      backgroundColor: '#ffffff',
      borderColor: '#111111',
      borderWidth: 1,
      textStyle: { color: '#111111', width: 260, overflow: 'break' as const },
      extraCssText: 'max-width:300px;white-space:normal;box-shadow:0 12px 28px rgba(0,0,0,.12);border-radius:4px;',
      formatter: (params: any) => {
        if (!params.data?._nodeId) return params.name
        return `<strong>${params.name}</strong><br/>点击查看技术详情与前置链`
      },
    },
    series: [{
      type: 'graph',
      layout: 'none',
      roam: true,
      zoom: 0.55,
      center: [4.5 * COL_W, 0],
      data,
      edges: edgeData,
      edgeSymbol: ['none', 'arrow'],
      edgeSymbolSize: 7,
      emphasis: { disabled: true },
      silent: false,
    }],
  }
}

function renderTree() {
  chart?.setOption(buildOption() as any, true)
}

async function loadTree() {
  treeData.value = await fetchTree()
  renderTree()
}

async function refreshGraph() {
  highlight.value = null
  selectedDetail.value = null
  await loadTree()
  resetGraphView()
}

function resetGraphView() {
  chart?.setOption({
    series: [{
      zoom: 0.55,
      center: [4.5 * COL_W, 0],
    }],
  } as any)
}

function focusEra(rank: number) {
  chart?.setOption({
    series: [{
      zoom: 0.82,
      center: [(rank - 1) * COL_W, 0],
    }],
  } as any)
}

async function toggleGraphFullScreen() {
  graphFullScreen.value = !graphFullScreen.value
  await nextTick()
  chart?.resize()
}

async function showNode(id: number) {
  if (!treeData.value) return
  const [detail, chain] = await Promise.all([fetchNode(id), fetchPrerequisites(id)])
  highlight.value = { selectedId: detail.id, chainIds: new Set(chain.map(node => node.id)) }
  selectedDetail.value = detail
  renderTree()
}

function handleResize() {
  chart?.resize()
}

function handleFocus() {
  user.loadProfile()
}

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
.layout {
  display: flex;
  height: calc(100vh - 64px);
  min-height: 0;
  background: var(--bg);
}

.graph-workspace {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 14px;
  padding: 18px;
  overflow: hidden;
}

.intro-strip {
  display: grid;
  grid-template-columns: minmax(320px, 1.25fr) minmax(250px, 0.9fr) 260px;
  gap: 14px;
  align-items: stretch;
}

.intro-copy,
.metric-grid,
.workflow-card {
  border: 1px solid var(--line);
  background: var(--panel);
  box-shadow: var(--shadow-sm);
}

.intro-copy {
  padding: 18px 20px;
}

.tag-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  color: var(--muted);
  font-size: 11px;
  letter-spacing: 0.1em;
  text-transform: uppercase;
}

.accent-tag {
  display: inline-flex;
  align-items: center;
  min-height: 22px;
  padding: 0 8px;
  background: rgba(255, 87, 34, 0.1);
  border: 1px solid rgba(255, 87, 34, 0.25);
  color: var(--accent);
  font-weight: 800;
}

.intro-copy h1 {
  margin-top: 10px;
  font-size: clamp(32px, 4vw, 54px);
  line-height: 1;
  letter-spacing: 0;
}

.intro-copy p {
  max-width: 720px;
  margin-top: 12px;
  color: var(--ink-2);
  font-size: 14px;
  line-height: 1.8;
}

.metric-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
}

.metric {
  display: grid;
  align-content: center;
  min-height: 72px;
  padding: 12px 14px;
  border-right: 1px solid var(--line);
  border-bottom: 1px solid var(--line);
}

.metric:nth-child(2n) {
  border-right: 0;
}

.metric:nth-last-child(-n + 2) {
  border-bottom: 0;
}

.metric span {
  font-size: 26px;
  font-weight: 800;
  line-height: 1;
}

.metric strong {
  margin-top: 5px;
  color: var(--muted);
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.08em;
  text-transform: uppercase;
}

.workflow-card {
  padding: 14px;
}

.workflow-title {
  padding-bottom: 10px;
  border-bottom: 1px solid var(--line);
  font-size: 12px;
  font-weight: 800;
  letter-spacing: 0.1em;
  text-transform: uppercase;
}

.workflow-step {
  display: flex;
  align-items: center;
  gap: 9px;
  min-height: 30px;
  color: var(--ink-2);
  font-size: 12px;
}

.workflow-step span {
  color: var(--accent);
  font-weight: 800;
}

.graph-shell {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  border: 1px solid var(--line);
  background: var(--panel);
  box-shadow: var(--shadow-sm);
}

.graph-shell.fullscreen {
  position: fixed;
  inset: 76px 18px 18px;
  z-index: 60;
  box-shadow: var(--shadow-md);
}

.graph-toolbar {
  min-height: 46px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 0 14px;
  border-bottom: 1px solid var(--line);
  background: var(--surface);
}

.toolbar-title,
.toolbar-actions {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
  color: var(--ink-2);
  font-size: 12px;
  letter-spacing: 0.06em;
  text-transform: uppercase;
}

.toolbar-title strong {
  color: var(--ink);
}

.toolbar-actions {
  flex-wrap: wrap;
  justify-content: flex-end;
}

.selected-status {
  max-width: 220px;
  overflow: hidden;
  color: var(--muted);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.tool-btn {
  min-height: 28px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  border: 1px solid var(--line-strong);
  background: var(--panel);
  color: var(--ink-2);
  padding: 0 10px;
  font-size: 11px;
  font-weight: 800;
  letter-spacing: 0.04em;
  cursor: pointer;
  transition: border-color 0.16s ease, color 0.16s ease, background 0.16s ease;
}

.tool-btn:hover {
  border-color: var(--ink);
  background: var(--surface-2);
  color: var(--ink);
}

.tool-btn.icon-only {
  width: 30px;
  padding: 0;
  font-size: 14px;
}

.panel-symbol {
  color: var(--accent);
}

.live-dot {
  width: 7px;
  height: 7px;
  background: var(--success);
}

.graph-canvas-wrap {
  position: relative;
  flex: 1;
  min-height: 0;
  overflow: hidden;
  background-color: #fafafa;
  background-image: radial-gradient(#d7d7d7 1.15px, transparent 1.15px);
  background-size: 24px 24px;
}

.chart-area {
  position: absolute;
  inset: 0;
  flex: 1;
  min-width: 0;
  min-height: 360px;
}

.era-legend {
  position: absolute;
  left: 18px;
  bottom: 18px;
  max-width: min(540px, calc(100% - 36px));
  border: 1px solid var(--line);
  background: rgba(255, 255, 255, 0.94);
  box-shadow: var(--shadow-sm);
  padding: 12px 14px;
}

.legend-title {
  display: block;
  margin-bottom: 10px;
  color: var(--accent);
  font-size: 11px;
  font-weight: 800;
  letter-spacing: 0.1em;
  text-transform: uppercase;
}

.legend-items {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 12px;
}

.legend-item {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  max-width: 160px;
  border: 0;
  background: transparent;
  color: var(--ink-2);
  font-size: 12px;
  cursor: pointer;
}

.legend-item:hover {
  color: var(--ink);
}

.legend-dot {
  width: 9px;
  height: 9px;
  flex: 0 0 auto;
}

.legend-item span:nth-child(2) {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.legend-item strong {
  color: var(--muted);
  font-size: 11px;
}

@media (max-width: 1180px) {
  .intro-strip {
    grid-template-columns: minmax(320px, 1fr) 260px;
  }

  .workflow-card {
    display: none;
  }
}

@media (max-width: 920px) {
  .layout {
    flex-direction: column;
    overflow: auto;
  }

  .graph-workspace {
    overflow: visible;
  }

  .intro-strip {
    grid-template-columns: 1fr;
  }

  .graph-shell.fullscreen {
    inset: 72px 12px 12px;
  }

  .era-legend {
    display: none;
  }
}
</style>
