<template>
  <AppHeader
    @open-login="showLogin = true"
    @open-member="showMemberModal"
    @focus-ai="openAiGuide"
  />

  <main class="layout">
    <section class="graph-workspace">
      <section class="overview-strip" aria-label="科技树总览">
        <div class="overview-copy">
          <span class="accent-tag">SPARROW TECH TREE</span>
          <h1>人类科技树</h1>
          <p>按依赖关系探索技术演进，快速定位节点、追踪前置知识，并把当前上下文交给 AI 向导继续追问。</p>
        </div>

        <div class="overview-side">
          <div class="metric-row" aria-label="图谱指标">
            <div class="metric"><strong>{{ nodeCount }}</strong><span>节点</span></div>
            <div class="metric"><strong>{{ edgeCount }}</strong><span>关系</span></div>
            <div class="metric"><strong>{{ progressCounts.mastered }}</strong><span>已掌握</span></div>
            <div class="metric"><strong>{{ premiumCount }}</strong><span>深度内容</span></div>
          </div>

          <div class="knowledge-status" aria-label="知识库状态">
            <Database :size="14" />
            <span>{{ knowledgeStatusText }}</span>
            <strong>{{ ragStatusText }}</strong>
          </div>
        </div>
      </section>

      <section class="graph-shell" :class="{ fullscreen: graphFullScreen }" aria-label="科技图谱">
        <div class="graph-toolbar">
          <div class="toolbar-title">
            <MapPinned :size="16" />
            <strong>科技图谱</strong>
            <span>{{ nodeCount || '--' }} 个节点</span>
          </div>

          <div class="search-box" @keydown.down.prevent="moveSearch(1)" @keydown.up.prevent="moveSearch(-1)">
            <Search :size="15" />
            <input
              v-model="searchQuery"
              type="search"
              placeholder="搜索技术、时代、关键词"
              autocomplete="off"
              @focus="searchOpen = true"
              @keydown.enter.prevent="confirmSearch"
              @keydown.esc.prevent="searchOpen = false"
            />
            <button v-if="searchQuery" class="clear-search" type="button" title="清空搜索" @click="clearSearch">
              <X :size="14" />
            </button>

            <div v-if="searchOpen && searchResults.length" class="search-results">
              <button
                v-for="(node, index) in searchResults"
                :key="node.id"
                type="button"
                :class="{ active: index === activeSearchIndex }"
                @mousedown.prevent="selectSearchResult(node)"
              >
                <span>{{ node.name }}</span>
                <small>{{ node.era }} · {{ node.yearLabel }}</small>
              </button>
            </div>
          </div>

          <div class="toolbar-actions" aria-label="图谱控制">
            <span class="selected-status">
              {{ selectedStatusText }}
            </span>
            <button class="tool-btn" type="button" title="刷新图谱" :disabled="treeLoading" @click="refreshGraph">
              <RefreshCcw :size="15" />
              <span>刷新</span>
            </button>
            <button class="tool-btn icon-only" type="button" title="重置视图" @click="resetGraphView">
              <RotateCcw :size="16" />
            </button>
            <button
              class="tool-btn icon-only"
              type="button"
              :title="graphFullScreen ? '退出全屏' : '全屏查看'"
              @click="toggleGraphFullScreen"
            >
              <Minimize2 v-if="graphFullScreen" :size="16" />
              <Maximize2 v-else :size="16" />
            </button>
            <span class="live-dot" title="服务在线"></span>
          </div>
        </div>

        <div class="graph-canvas-wrap">
          <div ref="chartRef" class="chart-area"></div>

          <div v-if="treeLoading" class="graph-overlay">
            <LoaderCircle class="spin" :size="22" />
            <strong>正在加载科技树</strong>
            <span>整理节点、关系和时代分组</span>
          </div>

          <div v-else-if="treeError" class="graph-overlay error">
            <AlertTriangle :size="22" />
            <strong>图谱加载失败</strong>
            <span>{{ treeError }}</span>
            <button type="button" @click="loadTree">重试</button>
          </div>

          <div v-if="eraLegend.length" class="era-rail" aria-label="时代导航">
            <div class="era-rail-head">
              <Route :size="14" />
              <span>时代导航</span>
            </div>
            <div class="era-track">
              <button
                v-for="era in eraLegend"
                :key="era.rank"
                type="button"
                :class="{ active: era.rank === activeEraRank }"
                :style="{ '--era-color': era.color }"
                @click="focusEra(era.rank)"
              >
                <span class="era-dot"></span>
                <strong>{{ era.name }}</strong>
                <small>{{ era.count }}</small>
              </button>
            </div>
          </div>
        </div>
      </section>
    </section>

    <GraphPanel
      :detail="selectedDetail"
      :preview="selectedPreview"
      :path-nodes="pathNodes"
      :learning-active="learningActive"
      :learning-index="learningIndex"
      :learning-total="learningTotal"
      :loading="nodeLoading"
      :error="nodeError"
      :progress="currentProgress"
      :era-color="currentEraColor"
      :floating="graphFullScreen"
      @select="selectFromPanel"
      @start-path="startLearningPath"
      @path-prev="goLearningPrev"
      @path-next="goLearningNext"
      @path-exit="exitLearningPath"
      @retry="retrySelectedNode"
      @set-progress="setCurrentProgress"
      @add-compare="addCurrentToCompare"
      @open-member="showMemberModal"
    />

    <section v-if="compareNodes.length" class="compare-dock" aria-label="技术对比">
      <div class="compare-head">
        <div>
          <GitCompare :size="15" />
          <strong>技术对比</strong>
          <span>{{ compareNodes.length }}/2</span>
        </div>
        <button type="button" title="清空对比" @click="clearCompare">
          <X :size="15" />
        </button>
      </div>

      <div class="compare-nodes">
        <article v-for="node in compareNodes" :key="node.id">
          <button class="remove-compare" type="button" title="移出对比" @click="removeCompare(node.id)">
            <X :size="13" />
          </button>
          <span>{{ node.era }}</span>
          <strong>{{ node.name }}</strong>
          <small>{{ node.summary }}</small>
        </article>
      </div>

      <div v-if="compareNodes.length === 2" class="compare-result">
        <div>
          <span>共同前置</span>
          <p v-if="commonPrerequisites.length">{{ commonPrerequisites.map(node => node.name).join('、') }}</p>
          <p v-else>暂无共同前置节点</p>
        </div>
        <div>
          <span>分叉方向</span>
          <p>{{ branchSummary }}</p>
        </div>
      </div>
      <p v-else class="compare-hint">再加入一个节点即可查看共同前置和分叉关系。</p>
    </section>
  </main>

  <AiDock ref="aiDockRef" :context-node="aiContextNode" />
  <LoginModal v-if="showLogin" @close="showLogin = false" />
  <MemberModal v-if="showMember" @close="showMember = false" />
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import * as echarts from 'echarts'
import {
  AlertTriangle,
  Database,
  GitCompare,
  LoaderCircle,
  MapPinned,
  Maximize2,
  Minimize2,
  RefreshCcw,
  RotateCcw,
  Route,
  Search,
  X,
} from '@lucide/vue'
import AppHeader from '../../../app/components/AppHeader.vue'
import GraphPanel from '../components/GraphPanel.vue'
import AiDock from '../../ai/components/AiDock.vue'
import LoginModal from '../../user/components/LoginModal.vue'
import MemberModal from '../../trade/components/MemberModal.vue'
import { fetchTree, fetchNode, fetchPrerequisites, fetchKnowledgeStatus } from '../api'
import type { Tree, NodeBrief, NodeDetail, KnowledgeStatus } from '../types'
import { useUserStore } from '../../user/store'

const ERA_COLORS: Record<number, string> = {
  1: '#2f3a2f',
  2: '#51606f',
  3: '#b45309',
  4: '#0f766e',
  5: '#2563eb',
  6: '#7c3aed',
  7: '#c2410c',
  8: '#15803d',
  9: '#be123c',
  10: '#111827',
}
const COL_W = 230
const ROW_H = 68
const DEFAULT_VIEW = { zoom: 0.55, center: [4.5 * COL_W, 0] as [number, number] }
const PROGRESS_KEY = 'sparrow_node_progress'

type ProgressState = 'want' | 'read' | 'mastered'

const user = useUserStore()
const chartRef = ref<HTMLElement | null>(null)
let chart: echarts.ECharts | null = null
let latestNodeRequest = 0

const treeData = ref<Tree | null>(null)
const selectedDetail = ref<NodeDetail | null>(null)
const selectedPreview = ref<NodeBrief | null>(null)
const selectedChain = ref<NodeBrief[]>([])
const highlight = ref<{ selectedId: number; chainIds: Set<number> } | null>(null)
const learningPath = ref<{ active: boolean; nodes: NodeBrief[]; index: number }>({
  active: false,
  nodes: [],
  index: 0,
})
const currentView = ref({ ...DEFAULT_VIEW })
const progressMap = ref<Record<number, ProgressState>>({})
const compareNodes = ref<NodeBrief[]>([])
const compareChains = ref<Record<number, NodeBrief[]>>({})
const compareDetails = ref<Record<number, NodeDetail>>({})
const knowledgeStatus = ref<KnowledgeStatus | null>(null)

const treeLoading = ref(false)
const treeError = ref('')
const nodeLoading = ref(false)
const nodeError = ref('')
const knowledgeError = ref('')
const showLogin = ref(false)
const showMember = ref(false)
const graphFullScreen = ref(false)
const searchQuery = ref('')
const searchOpen = ref(false)
const activeSearchIndex = ref(0)
const aiDockRef = ref<InstanceType<typeof AiDock> | null>(null)

const nodeCount = computed(() => treeData.value?.nodes.length ?? 0)
const edgeCount = computed(() => treeData.value?.edges.length ?? 0)
const eraCount = computed(() => new Set(treeData.value?.nodes.map(node => node.eraRank) ?? []).size)
const premiumCount = computed(() => treeData.value?.nodes.filter(node => node.premium).length ?? 0)
const nodeById = computed(() => new Map((treeData.value?.nodes ?? []).map(node => [node.id, node])))
const activeEraRank = computed(() => selectedDetail.value?.eraRank ?? selectedPreview.value?.eraRank ?? null)
const currentEraColor = computed(() => activeEraRank.value ? ERA_COLORS[activeEraRank.value] || '#111111' : '#ff5722')
const aiContextNode = computed(() => selectedDetail.value ? detailToBrief(selectedDetail.value) : selectedPreview.value)
const learningActive = computed(() => learningPath.value.active && learningPath.value.nodes.length > 0)
const learningIndex = computed(() => learningActive.value ? learningPath.value.index : -1)
const learningTotal = computed(() => learningActive.value ? learningPath.value.nodes.length : 0)
const learningCurrentNode = computed(() => {
  if (!learningActive.value) return null
  return learningPath.value.nodes[learningPath.value.index] ?? null
})
const currentProgress = computed(() => {
  const id = selectedDetail.value?.id ?? selectedPreview.value?.id
  return id ? progressMap.value[id] ?? null : null
})
const progressCounts = computed(() => {
  const values = Object.values(progressMap.value)
  return {
    want: values.filter(value => value === 'want').length,
    read: values.filter(value => value === 'read').length,
    mastered: values.filter(value => value === 'mastered').length,
  }
})
const knowledgeStatusText = computed(() => {
  if (knowledgeError.value) return '知识库状态暂不可用'
  if (!knowledgeStatus.value) return '正在读取知识库状态'
  if (!knowledgeStatus.value.ragDocumentCount) return '暂无爬虫语料'
  return `${knowledgeStatus.value.ragDocumentCount} 条语料 · ${knowledgeStatus.value.ragUpdatedAt || '未记录更新时间'}`
})
const ragStatusText = computed(() => {
  if (!knowledgeStatus.value) return '--'
  return knowledgeStatus.value.ragIndexed
    ? `RAG 已索引 ${knowledgeStatus.value.ragChunkCount ?? 0} 块`
    : 'RAG 待重建'
})

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

const pathNodes = computed(() => {
  if (learningActive.value) return learningPath.value.nodes
  const nodes: NodeBrief[] = []
  const seen = new Set<number>()
  for (const node of selectedChain.value) {
    if (!seen.has(node.id)) {
      nodes.push(node)
      seen.add(node.id)
    }
  }
  const current = selectedDetail.value ? detailToBrief(selectedDetail.value) : selectedPreview.value
  if (current && !seen.has(current.id)) nodes.push(current)
  return nodes.sort((a, b) => a.eraRank - b.eraRank || a.id - b.id)
})

const selectedStatusText = computed(() => {
  if (learningActive.value && learningCurrentNode.value) {
    return `路径第 ${learningIndex.value + 1} 步 · ${learningCurrentNode.value.name}`
  }
  if (selectedDetail.value) return selectedDetail.value.name
  if (selectedPreview.value) return selectedPreview.value.name
  return '拖拽 / 缩放 / 点击节点'
})

const commonPrerequisites = computed(() => {
  if (compareNodes.value.length !== 2) return []
  const [a, b] = compareNodes.value
  const left = compareChains.value[a.id] ?? []
  const right = compareChains.value[b.id] ?? []
  const rightIds = new Set(right.map(node => node.id))
  return left
    .filter(node => rightIds.has(node.id))
    .sort((x, y) => x.eraRank - y.eraRank || x.id - y.id)
})

const branchSummary = computed(() => {
  if (compareNodes.value.length !== 2) return ''
  return compareNodes.value
    .map(node => {
      const unlocks = compareDetails.value[node.id]?.unlocks ?? []
      const names = unlocks.slice(0, 3).map(item => item.name).join('、')
      return `${node.name}: ${names || '暂无后续解锁'}`
    })
    .join('；')
})

const searchResults = computed(() => {
  const query = normalize(searchQuery.value)
  if (!query) return []
  return (treeData.value?.nodes ?? [])
    .map(node => ({ node, score: scoreNode(node, query) }))
    .filter(item => item.score > 0)
    .sort((a, b) => b.score - a.score || a.node.eraRank - b.node.eraRank || a.node.id - b.node.id)
    .slice(0, 8)
    .map(item => item.node)
})

watch(searchResults, () => {
  activeSearchIndex.value = 0
})

function normalize(value: string) {
  return value.trim().toLocaleLowerCase()
}

function scoreNode(node: NodeBrief, query: string) {
  const name = normalize(node.name)
  const era = normalize(node.era)
  const summary = normalize(node.summary)
  const code = normalize(node.code)
  const year = normalize(node.yearLabel || '')
  if (name === query || code === query) return 100
  if (name.startsWith(query)) return 80
  if (name.includes(query)) return 64
  if (era.includes(query)) return 44
  if (year.includes(query)) return 36
  if (summary.includes(query)) return 24
  return 0
}

function detailToBrief(detail: NodeDetail): NodeBrief {
  return {
    id: detail.id,
    code: detail.code,
    name: detail.name,
    era: detail.era,
    eraRank: detail.eraRank,
    yearLabel: detail.yearLabel,
    summary: detail.summary,
    premium: detail.premium,
  }
}

function highlightIdsFor(chain: NodeBrief[]) {
  const nodes = learningActive.value ? learningPath.value.nodes : chain
  return new Set(nodes.map(node => node.id))
}

function nodeHighlight(selectedId: number, chain: NodeBrief[]) {
  return { selectedId, chainIds: highlightIdsFor(chain) }
}

function clearLearningPath() {
  learningPath.value = { active: false, nodes: [], index: 0 }
}

function loadProgress() {
  try {
    const raw = localStorage.getItem(PROGRESS_KEY)
    progressMap.value = raw ? JSON.parse(raw) : {}
  } catch {
    progressMap.value = {}
  }
}

function saveProgress() {
  localStorage.setItem(PROGRESS_KEY, JSON.stringify(progressMap.value))
}

function setCurrentProgress(state: ProgressState) {
  const id = selectedDetail.value?.id ?? selectedPreview.value?.id
  if (!id) return
  progressMap.value = { ...progressMap.value, [id]: state }
  saveProgress()
}

function addCurrentToCompare() {
  const current = selectedDetail.value ? detailToBrief(selectedDetail.value) : selectedPreview.value
  if (!current) return
  if (compareNodes.value.some(node => node.id === current.id)) return
  const next = compareNodes.value.length >= 2 ? [compareNodes.value[1], current] : [...compareNodes.value, current]
  compareNodes.value = next
  compareChains.value = { ...compareChains.value, [current.id]: [...selectedChain.value] }
  if (selectedDetail.value) {
    compareDetails.value = { ...compareDetails.value, [current.id]: selectedDetail.value }
  }
}

function removeCompare(id: number) {
  compareNodes.value = compareNodes.value.filter(node => node.id !== id)
  const { [id]: _chain, ...chains } = compareChains.value
  const { [id]: _detail, ...details } = compareDetails.value
  compareChains.value = chains
  compareDetails.value = details
}

function clearCompare() {
  compareNodes.value = []
  compareChains.value = {}
  compareDetails.value = {}
}

async function loadKnowledgeStatus() {
  knowledgeError.value = ''
  try {
    knowledgeStatus.value = await fetchKnowledgeStatus()
  } catch (error: any) {
    knowledgeError.value = error.message || '状态读取失败'
  }
}

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
  const learningNodeId = learningCurrentNode.value?.id ?? null
  const learningIds = learningActive.value ? new Set(learningPath.value.nodes.map(node => node.id)) : null
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
      const inLearningPath = learningIds?.has(node.id) ?? false
      const isLearningCurrent = node.id === learningNodeId
      const labelLength = Array.from(node.name).length
      return {
        id: String(node.id),
        name: node.name,
        x: pos[node.id].x,
        y: pos[node.id].y,
        symbol: 'roundRect',
        symbolSize: [
          Math.max(labelLength * 14 + (node.premium ? 48 : 30) + (isLearningCurrent ? 20 : 0), 84),
          isLearningCurrent ? 42 : 34,
        ],
        itemStyle: {
          color: ERA_COLORS[node.eraRank] || '#111111',
          opacity: dim ? 0.18 : 1,
          borderColor: isLearningCurrent ? '#ff5722' : isSelected ? '#000000' : inChain ? '#ff5722' : '#ffffff',
          borderWidth: isLearningCurrent ? 4 : isSelected ? 3 : inChain ? 2 : 1,
          shadowBlur: isLearningCurrent ? 26 : inChain ? 14 : 0,
          shadowColor: isLearningCurrent ? 'rgba(255, 87, 34, 0.56)' : 'rgba(255, 87, 34, 0.34)',
        },
        label: {
          show: true,
          color: '#ffffff',
          fontSize: 12,
          fontWeight: isLearningCurrent ? 900 : 700,
          formatter: `${node.premium ? 'PRO · ' : ''}${node.name}`,
          opacity: dim && !inLearningPath ? 0.28 : 1,
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
        const node = nodeById.value.get(params.data._nodeId)
        const guideIndex = learningPath.value.nodes.findIndex(item => item.id === params.data._nodeId)
        const guideLine = learningActive.value && guideIndex >= 0
          ? `<br/><em>路径第 ${guideIndex + 1} / ${learningTotal.value} 步${params.data._nodeId === learningNodeId ? ' · 当前' : ''}</em>`
          : ''
        return `<strong>${params.name}</strong>${guideLine}<br/>${node?.era ?? ''} · ${node?.yearLabel ?? ''}<br/>点击查看技术详情与前置链`
      },
    },
    series: [{
      type: 'graph',
      layout: 'none',
      roam: true,
      zoom: currentView.value.zoom,
      center: currentView.value.center,
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
  treeLoading.value = true
  treeError.value = ''
  try {
    treeData.value = await fetchTree()
    renderTree()
  } catch (error: any) {
    treeError.value = error.message || '无法连接图谱服务'
  } finally {
    treeLoading.value = false
  }
}

async function refreshGraph() {
  const selectedId = selectedDetail.value?.id ?? selectedPreview.value?.id
  const keepLearning = learningActive.value
  await loadTree()
  await loadKnowledgeStatus()
  if (selectedId && nodeById.value.has(selectedId)) {
    await showNode(selectedId, { focus: false, fromLearning: keepLearning })
  }
}

function resetGraphView() {
  currentView.value = { ...DEFAULT_VIEW }
  chart?.setOption({
    series: [{
      zoom: currentView.value.zoom,
      center: currentView.value.center,
    }],
  } as any)
}

function focusEra(rank: number) {
  currentView.value = { zoom: 0.82, center: [(rank - 1) * COL_W, 0] }
  chart?.setOption({
    series: [{
      zoom: currentView.value.zoom,
      center: currentView.value.center,
    }],
  } as any)
}

function centerNode(id: number, zoom = 0.92) {
  const node = nodeById.value.get(id)
  if (!node || !treeData.value) return
  const pos = layoutNodes(treeData.value.nodes)[id]
  if (!pos) return
  currentView.value = { zoom, center: [pos.x, pos.y] }
  chart?.setOption({
    series: [{
      zoom: currentView.value.zoom,
      center: currentView.value.center,
    }],
  } as any)
}

async function toggleGraphFullScreen() {
  graphFullScreen.value = !graphFullScreen.value
  await nextTick()
  chart?.resize()
}

async function showNode(id: number, options: { focus?: boolean; fromLearning?: boolean } = {}) {
  if (!treeData.value) return
  if (learningActive.value && !options.fromLearning) {
    clearLearningPath()
  }
  const requestId = ++latestNodeRequest
  const preview = nodeById.value.get(id) ?? null
  selectedPreview.value = preview
  selectedDetail.value = null
  selectedChain.value = []
  nodeLoading.value = true
  nodeError.value = ''
  if (options.focus) centerNode(id)

  try {
    const [detail, chain] = await Promise.all([fetchNode(id), fetchPrerequisites(id)])
    if (requestId !== latestNodeRequest) return
    highlight.value = nodeHighlight(detail.id, chain)
    selectedDetail.value = detail
    selectedPreview.value = detailToBrief(detail)
    selectedChain.value = chain
    if (compareNodes.value.some(node => node.id === detail.id)) {
      compareChains.value = { ...compareChains.value, [detail.id]: chain }
      compareDetails.value = { ...compareDetails.value, [detail.id]: detail }
    }
    renderTree()
  } catch (error: any) {
    if (requestId !== latestNodeRequest) return
    nodeError.value = error.message || '节点详情加载失败'
    highlight.value = nodeHighlight(id, [])
    renderTree()
  } finally {
    if (requestId === latestNodeRequest) nodeLoading.value = false
  }
}

function retrySelectedNode() {
  const id = selectedPreview.value?.id ?? selectedDetail.value?.id
  if (id) void showNode(id, { focus: false, fromLearning: learningActive.value })
}

function startLearningPath() {
  const nodes = [...pathNodes.value]
  if (!nodes.length) return
  learningPath.value = { active: true, nodes, index: 0 }
  void goLearningTo(0)
}

async function goLearningTo(index: number) {
  if (!learningActive.value) return
  const nextIndex = Math.min(Math.max(index, 0), learningPath.value.nodes.length - 1)
  const node = learningPath.value.nodes[nextIndex]
  if (!node) return
  learningPath.value = { ...learningPath.value, index: nextIndex }
  await showNode(node.id, { focus: true, fromLearning: true })
}

function goLearningPrev() {
  void goLearningTo(learningPath.value.index - 1)
}

function goLearningNext() {
  void goLearningTo(learningPath.value.index + 1)
}

function exitLearningPath() {
  if (!learningActive.value) return
  clearLearningPath()
  const id = selectedDetail.value?.id ?? selectedPreview.value?.id
  highlight.value = id ? nodeHighlight(id, selectedChain.value) : null
  renderTree()
}

function selectFromPanel(id: number) {
  if (learningActive.value) {
    const stepIndex = learningPath.value.nodes.findIndex(node => node.id === id)
    if (stepIndex >= 0) {
      void goLearningTo(stepIndex)
      return
    }
  }
  void showNode(id, { focus: true })
}

function moveSearch(delta: number) {
  if (!searchResults.value.length) return
  searchOpen.value = true
  activeSearchIndex.value = (activeSearchIndex.value + delta + searchResults.value.length) % searchResults.value.length
}

function confirmSearch() {
  const node = searchResults.value[activeSearchIndex.value] ?? searchResults.value[0]
  if (node) selectSearchResult(node)
}

function selectSearchResult(node: NodeBrief) {
  searchQuery.value = node.name
  searchOpen.value = false
  void showNode(node.id, { focus: true })
}

function clearSearch() {
  searchQuery.value = ''
  searchOpen.value = false
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

function handleResize() {
  chart?.resize()
}

function handleFocus() {
  user.loadProfile()
}

function isTypingTarget(target: EventTarget | null) {
  if (!(target instanceof HTMLElement)) return false
  const tag = target.tagName.toLowerCase()
  return tag === 'input' || tag === 'textarea' || tag === 'select' || target.isContentEditable
}

function handleKeydown(event: KeyboardEvent) {
  if (!learningActive.value || isTypingTarget(event.target)) return
  if (event.key === 'ArrowLeft') {
    event.preventDefault()
    goLearningPrev()
  } else if (event.key === 'ArrowRight') {
    event.preventDefault()
    goLearningNext()
  } else if (event.key === 'Escape') {
    event.preventDefault()
    exitLearningPath()
  }
}

onMounted(async () => {
  if (chartRef.value) {
    chart = echarts.init(chartRef.value)
    chart.on('click', (params: any) => {
      if (params.dataType === 'node' && params.data?._nodeId) void showNode(params.data._nodeId, { focus: false })
    })
    chart.on('graphRoam', updateCurrentViewFromChart)
    window.addEventListener('resize', handleResize)
    window.addEventListener('focus', handleFocus)
    window.addEventListener('keydown', handleKeydown)
  }
  loadProgress()
  await user.loadProfile()
  await Promise.all([loadTree(), loadKnowledgeStatus()])
})

onUnmounted(() => {
  window.removeEventListener('resize', handleResize)
  window.removeEventListener('focus', handleFocus)
  window.removeEventListener('keydown', handleKeydown)
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
  gap: 12px;
  padding: 14px;
  overflow: hidden;
}

.overview-strip {
  display: grid;
  grid-template-columns: minmax(320px, 1fr) auto;
  gap: 14px;
  align-items: center;
  border: 1px solid var(--line);
  background: var(--panel);
  box-shadow: var(--shadow-sm);
  padding: 12px 14px;
}

.overview-copy {
  min-width: 0;
}

.accent-tag {
  display: inline-flex;
  align-items: center;
  min-height: 22px;
  padding: 0 8px;
  background: rgba(255, 87, 34, 0.1);
  border: 1px solid rgba(255, 87, 34, 0.25);
  color: var(--accent);
  font-size: 11px;
  font-weight: 800;
  letter-spacing: 0.1em;
}

.overview-copy h1 {
  margin-top: 7px;
  font-size: 28px;
  line-height: 1.1;
  letter-spacing: 0;
}

.overview-copy p {
  margin-top: 6px;
  max-width: 760px;
  color: var(--ink-2);
  font-size: 13px;
  line-height: 1.7;
}

.metric-row {
  display: grid;
  grid-template-columns: repeat(4, minmax(82px, 1fr));
  border: 1px solid var(--line);
  background: var(--surface);
}

.overview-side {
  display: grid;
  gap: 8px;
  min-width: min(520px, 100%);
}

.metric {
  display: grid;
  gap: 4px;
  padding: 10px 12px;
  border-right: 1px solid var(--line);
}

.metric:last-child {
  border-right: 0;
}

.metric strong {
  font-size: 22px;
  line-height: 1;
}

.metric span {
  color: var(--muted);
  font-size: 11px;
  font-weight: 800;
  letter-spacing: 0.08em;
}

.knowledge-status {
  min-height: 34px;
  display: grid;
  grid-template-columns: auto 1fr auto;
  align-items: center;
  gap: 8px;
  border: 1px solid var(--line);
  background: var(--panel);
  padding: 0 10px;
  color: var(--ink-2);
  font-size: 12px;
}

.knowledge-status svg {
  color: var(--accent);
}

.knowledge-status span {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.knowledge-status strong {
  color: var(--ink);
  font-size: 11px;
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
  min-height: 52px;
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 12px;
  padding: 9px 14px;
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
}

.toolbar-title {
  flex: 1 1 170px;
}

.toolbar-title strong {
  color: var(--ink);
}

.toolbar-title svg {
  color: var(--accent);
}

.search-box {
  position: relative;
  flex: 999 1 300px;
  height: 34px;
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
  border: 1px solid var(--line-strong);
  background: var(--panel);
  padding: 0 9px;
}

.search-box:focus-within {
  border-color: var(--ink);
}

.search-box svg {
  flex: 0 0 auto;
  color: var(--muted);
}

.search-box input {
  flex: 1;
  min-width: 0;
  height: 100%;
  border: 0;
  outline: none;
  background: transparent;
  color: var(--ink);
  font-size: 13px;
}

.clear-search {
  display: grid;
  place-items: center;
  width: 24px;
  height: 24px;
  border: 0;
  background: transparent;
  color: var(--muted);
  cursor: pointer;
}

.search-results {
  position: absolute;
  top: calc(100% + 6px);
  left: 0;
  right: 0;
  z-index: 20;
  border: 1px solid var(--ink);
  background: var(--panel);
  box-shadow: var(--shadow-md);
  padding: 4px;
}

.search-results button {
  width: 100%;
  display: grid;
  gap: 3px;
  border: 0;
  background: transparent;
  padding: 9px 10px;
  text-align: left;
  cursor: pointer;
}

.search-results button:hover,
.search-results button.active {
  background: rgba(255, 87, 34, 0.08);
}

.search-results span {
  color: var(--ink);
  font-size: 13px;
  font-weight: 800;
}

.search-results small {
  color: var(--muted);
  font-size: 11px;
}

.toolbar-actions {
  flex: 1 1 260px;
  justify-content: flex-end;
  flex-wrap: wrap;
}

.selected-status {
  max-width: 220px;
  overflow: hidden;
  color: var(--muted);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.tool-btn {
  min-height: 30px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  border: 1px solid var(--line-strong);
  background: var(--panel);
  color: var(--ink-2);
  padding: 0 10px;
  font-size: 12px;
  font-weight: 800;
  cursor: pointer;
  transition: border-color 0.16s ease, color 0.16s ease, background 0.16s ease;
}

.tool-btn:hover:not(:disabled) {
  border-color: var(--ink);
  background: var(--surface-2);
  color: var(--ink);
}

.tool-btn:disabled {
  color: var(--muted);
  cursor: default;
}

.tool-btn.icon-only {
  width: 32px;
  padding: 0;
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
  min-width: 0;
  min-height: 360px;
}

.graph-overlay {
  position: absolute;
  inset: 0;
  z-index: 10;
  display: grid;
  place-content: center;
  justify-items: center;
  gap: 8px;
  background: rgba(255, 255, 255, 0.82);
  color: var(--ink);
  text-align: center;
}

.graph-overlay span {
  color: var(--ink-2);
  font-size: 13px;
}

.graph-overlay button {
  min-height: 32px;
  border: 1px solid var(--ink);
  background: var(--ink);
  color: var(--bg);
  padding: 0 14px;
  font-weight: 800;
  cursor: pointer;
}

.graph-overlay.error svg {
  color: var(--danger);
}

.spin {
  animation: spin 0.9s linear infinite;
}

.era-rail {
  position: absolute;
  left: 18px;
  right: 18px;
  bottom: 18px;
  z-index: 8;
  display: grid;
  gap: 10px;
  border: 1px solid var(--line);
  background: rgba(255, 255, 255, 0.94);
  box-shadow: var(--shadow-sm);
  padding: 12px 14px;
}

.era-rail-head {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  color: var(--accent);
  font-size: 11px;
  font-weight: 800;
  letter-spacing: 0.1em;
}

.era-track {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(92px, 1fr));
  gap: 8px;
}

.era-track button {
  min-width: 0;
  display: grid;
  grid-template-columns: auto 1fr auto;
  align-items: center;
  gap: 6px;
  border: 1px solid var(--line);
  background: var(--panel);
  padding: 7px 8px;
  color: var(--ink-2);
  cursor: pointer;
  transition: border-color 0.16s ease, background 0.16s ease, color 0.16s ease;
}

.era-track button:hover,
.era-track button.active {
  border-color: var(--era-color);
  background: color-mix(in srgb, var(--era-color) 9%, white);
  color: var(--ink);
}

.era-dot {
  width: 9px;
  height: 9px;
  background: var(--era-color);
}

.era-track strong {
  min-width: 0;
  overflow: hidden;
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.era-track small {
  color: var(--muted);
  font-size: 11px;
  font-weight: 800;
}

.compare-dock {
  position: fixed;
  left: 18px;
  bottom: 18px;
  z-index: 90;
  width: min(560px, calc(100vw - 36px));
  border: 1px solid var(--ink);
  background: var(--panel);
  box-shadow: var(--shadow-md);
}

.compare-head {
  min-height: 40px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding: 0 12px;
  border-bottom: 1px solid var(--line);
  background: var(--surface);
}

.compare-head div,
.compare-head button {
  display: inline-flex;
  align-items: center;
  gap: 7px;
}

.compare-head svg {
  color: var(--accent);
}

.compare-head span {
  color: var(--muted);
  font-size: 12px;
}

.compare-head button {
  border: 0;
  background: transparent;
  color: var(--muted);
  cursor: pointer;
}

.compare-nodes {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
  padding: 12px;
}

.compare-nodes article {
  position: relative;
  display: grid;
  gap: 6px;
  min-height: 106px;
  border: 1px solid var(--line);
  background: var(--surface);
  padding: 12px;
}

.compare-nodes article span {
  color: var(--accent);
  font-size: 11px;
  font-weight: 800;
  letter-spacing: 0.08em;
}

.compare-nodes article strong {
  font-size: 16px;
}

.compare-nodes article small {
  display: -webkit-box;
  overflow: hidden;
  color: var(--ink-2);
  font-size: 12px;
  line-height: 1.6;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
}

.remove-compare {
  position: absolute;
  top: 8px;
  right: 8px;
  display: grid;
  place-items: center;
  width: 22px;
  height: 22px;
  border: 1px solid var(--line-strong);
  background: var(--panel);
  color: var(--muted);
  cursor: pointer;
}

.compare-result {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 10px;
  padding: 0 12px 12px;
}

.compare-result div,
.compare-hint {
  border-top: 1px solid var(--line);
  padding-top: 10px;
}

.compare-result span {
  color: var(--accent);
  font-size: 11px;
  font-weight: 800;
  letter-spacing: 0.08em;
}

.compare-result p,
.compare-hint {
  margin-top: 5px;
  color: var(--ink-2);
  font-size: 12px;
  line-height: 1.7;
}

.compare-hint {
  margin: 0 12px 12px;
}

@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}

@media (max-width: 1180px) {
  .overview-strip {
    grid-template-columns: 1fr;
  }

  .graph-toolbar {
    grid-template-columns: 1fr;
    padding: 10px 12px;
  }

  .toolbar-actions {
    justify-content: flex-start;
  }
}

@media (max-width: 920px) {
  .layout {
    flex-direction: column;
    overflow: auto;
  }

  .graph-workspace {
    min-height: 68vh;
    overflow: visible;
  }

  .metric-row {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .knowledge-status {
    grid-template-columns: auto 1fr;
  }

  .knowledge-status strong {
    grid-column: 2;
  }

  .graph-shell.fullscreen {
    inset: 72px 12px 12px;
  }

  .era-rail {
    display: none;
  }

  .compare-dock {
    left: 12px;
    right: 12px;
    bottom: 12px;
    width: auto;
  }

  .compare-result,
  .compare-nodes {
    grid-template-columns: 1fr;
  }
}
</style>
