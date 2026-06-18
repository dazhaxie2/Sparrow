<template>
  <AppHeader
    @open-login="showLogin = true"
    @open-member="showMemberModal"
    @focus-ai="openAiGuide"
    @open-learning="showLearning = true"
    @open-settings="showSettings = true"
  />

  <main class="layout" :class="{ 'dialog-layout': graphMode === 'dialog' }">
    <aside v-if="graphMode === 'map'" class="side-rail" aria-label="知识图谱控制台">
      <div class="rail-head">
        <span class="accent-tag">SPARROW KNOWLEDGE GRAPH</span>
        <h1>点线知识图谱</h1>
        <p>按领域与时代探索技术关系</p>
      </div>

      <div class="rail-stats" aria-label="图谱指标">
        <div class="stat"><strong>{{ totalNodes }}</strong><span>节点</span></div>
        <div class="stat"><strong>{{ totalEdges }}</strong><span>关系</span></div>
        <div class="stat"><strong>{{ progressCounts.mastered }}</strong><span>已掌握</span></div>
        <div class="stat"><strong>{{ premiumCount }}</strong><span>深度</span></div>
      </div>

      <div class="rail-scroll">
        <section v-if="categories.length" class="rail-section">
          <div class="rail-label"><Layers :size="13" /><span>领域</span></div>
          <div class="rail-chips">
            <button type="button" :class="{ active: activeCategory === null }" @click="setCategory(null)">全部</button>
            <button
              v-for="cat in categories"
              :key="cat"
              type="button"
              :class="{ active: activeCategory === cat }"
              @click="setCategory(cat)"
            >{{ cat }}</button>
          </div>
        </section>

        <section v-if="eraLegend.length" class="rail-section">
          <div class="rail-label"><Route :size="13" /><span>时代</span></div>
          <div class="rail-eras">
            <button
              v-for="era in eraLegend"
              :key="era.rank"
              type="button"
              :class="{ active: era.rank === activeEraRank }"
              :style="{ '--era-color': era.color }"
              @click="focusEra(era.rank)"
            >
              <span class="era-dot"></span>
              <span class="era-name">{{ era.name }}</span>
              <small>{{ era.count }}</small>
            </button>
          </div>
        </section>

        <section class="rail-section">
          <div class="rail-label"><span>显示密度</span></div>
          <div class="rail-density">
            <button
              v-for="opt in LIMIT_OPTIONS"
              :key="opt"
              type="button"
              :class="{ active: graphLimit === opt }"
              @click="setLimit(opt)"
            >{{ opt }}</button>
          </div>
        </section>
      </div>
    </aside>

    <section class="graph-workspace">
      <section class="graph-shell" :class="{ fullscreen: graphFullScreen, 'dialog-mode': dialogActive }" aria-label="科技图谱">
        <div class="graph-toolbar">
          <div class="toolbar-title">
            <MapPinned :size="16" />
            <strong>{{ dialogActive ? '会话临时图谱' : '知识图谱' }}</strong>
            <span>{{ dialogActive ? `提取 ${nodeCount} 个相关节点` : `显示 ${nodeCount} / ${totalNodes}` }}</span>
          </div>

          <div class="mode-switch" aria-label="图谱模式">
            <button type="button" :class="{ active: graphMode === 'map' }" @click="switchGraphMode('map')">
              图谱
            </button>
            <button type="button" :class="{ active: graphMode === 'dialog' }" @click="switchGraphMode('dialog')">
              对话
            </button>
          </div>

          <div v-if="graphMode === 'map'" class="search-box" @keydown.down.prevent="moveSearch(1)" @keydown.up.prevent="moveSearch(-1)">
            <Search :size="15" />
            <input
              v-model="searchQuery"
              type="search"
              placeholder="搜索技术、关键词"
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

          <div v-else class="dialog-mode-status">
            <BrainCircuit :size="15" />
            <span>{{ dialogResult ? dialogResult.query : '关联节点实时构造' }}</span>
          </div>

          <div class="toolbar-actions" aria-label="图谱控制">
            <span class="selected-status">{{ selectedStatusText }}</span>
            <button class="tool-btn" type="button" title="刷新图谱" :disabled="treeLoading" @click="refreshGraph">
              <RefreshCcw :size="15" />
            </button>
            <button class="tool-btn" type="button" title="重置视图" @click="resetGraphView">
              <RotateCcw :size="16" />
            </button>
            <button
              class="tool-btn"
              type="button"
              :title="graphFullScreen ? '退出全屏' : '全屏查看'"
              @click="toggleGraphFullScreen"
            >
              <Minimize2 v-if="graphFullScreen" :size="16" />
              <Maximize2 v-else :size="16" />
            </button>
          </div>
        </div>

        <div class="graph-canvas-wrap">
          <div
            ref="chartRef"
            class="chart-area"
            :class="{ 'is-panning': graphPanning }"
            @pointerdown="startGraphPan"
            @pointermove="moveGraphPan"
            @pointerup="stopGraphPan"
            @pointercancel="cancelGraphPan"
            @lostpointercapture="cancelGraphPan"
            @wheel="onGraphWheel"
          ></div>

          <div v-if="treeLoading" class="graph-overlay">
            <LoaderCircle class="spin" :size="22" />
            <strong>正在加载知识图谱</strong>
            <span>整理节点、关系和时代分组</span>
          </div>

          <div v-else-if="treeError" class="graph-overlay error">
            <AlertTriangle :size="22" />
            <strong>图谱加载失败</strong>
            <span>{{ treeError }}</span>
            <button type="button" @click="loadTree">重试</button>
          </div>

          <div v-if="eraLegend.length && !treeLoading && !treeError" class="graph-legend" aria-label="时代图例">
            <span class="legend-title">ERA TYPES</span>
            <div class="legend-items">
              <button
                v-for="era in eraLegend"
                :key="era.rank"
                type="button"
                :class="{ active: era.rank === activeEraRank }"
                :style="{ '--era-color': era.color }"
                @click="focusEra(era.rank)"
              >
                <span class="legend-dot"></span>
                <span>{{ era.name }}</span>
                <small>{{ era.count }}</small>
              </button>
            </div>
          </div>

          <label v-if="hasInformativeEdgeLabels && !treeLoading && !treeError" class="edge-label-toggle">
            <input v-model="showEdgeLabels" type="checkbox" />
            <span></span>
            <strong>显示边名</strong>
          </label>

        </div>
      </section>
    </section>

    <GraphPanel
      v-if="graphMode === 'map'"
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

    <section v-else class="conversation-workbench" aria-label="关联节点对话工作台">
      <header class="workbench-header">
        <div>
          <BrainCircuit :size="17" />
          <strong>SPARROW AGENT</strong>
          <span>{{ dialogLoading ? 'BUILDING' : dialogActive ? 'GRAPH READY' : 'READY' }}</span>
        </div>
        <button type="button" title="返回图谱模式" @click="switchGraphMode('map')">
          <X :size="16" />
        </button>
      </header>

      <div class="workbench-steps" aria-label="工作流状态">
        <div class="active">
          <span>Step 1/2</span>
          <strong>对话提取</strong>
        </div>
        <div :class="{ active: dialogActive }">
          <span>Step 2/2</span>
          <strong>临时图谱</strong>
        </div>
        <small :class="{ live: dialogLoading || dialogActive }"></small>
      </div>

      <div ref="dialogFeedRef" class="dialog-feed">
        <div v-if="!dialogMessages.length" class="dialog-empty">
          <MessageSquareText :size="24" />
          <strong>与 Agent 对话</strong>
          <span>例如：蒸汽机如何影响铁路和电力？</span>
        </div>

        <article
          v-for="message in dialogMessages"
          :key="message.id"
          class="dialog-message"
          :class="message.role"
        >
          <div class="message-meta">
            <strong>{{ message.role === 'user' ? 'YOU' : 'AGENT' }}</strong>
            <span v-if="message.title">{{ message.title }}</span>
          </div>
          <p>{{ message.content }}</p>

          <div v-if="message.terms?.length" class="dialog-terms">
            <span v-for="term in message.terms" :key="term">{{ term }}</span>
          </div>

          <div v-if="message.nodes?.length" class="dialog-node-list">
            <button
              v-for="node in message.nodes.slice(0, 10)"
              :key="node.id"
              type="button"
              @click="showNode(node.id, { focus: true })"
            >
              <span>{{ node.name }}</span>
              <small>{{ node.era }}</small>
            </button>
          </div>
        </article>

        <div v-if="dialogLoading" class="dialog-state">
          <LoaderCircle class="spin" :size="16" />
          <span>BUILDING GRAPH MEMORY</span>
        </div>
        <div v-if="dialogError" class="dialog-error">{{ dialogError }}</div>
      </div>

      <form class="workbench-input" @submit.prevent="runDialogExtraction()">
        <textarea
          ref="dialogInputRef"
          v-model="dialogQuery"
          rows="3"
          placeholder="Ask Agent..."
          :disabled="dialogLoading"
          @keydown.enter.exact.prevent="runDialogExtraction()"
        ></textarea>
        <button type="submit" :disabled="dialogLoading || !dialogQuery.trim()" title="发送">
          <LoaderCircle v-if="dialogLoading" class="spin" :size="16" />
          <SendHorizontal v-else :size="16" />
        </button>
      </form>
    </section>

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
          <p v-if="commonPrerequisites.length">{{ commonPrerequisites.slice(0, 12).map(node => node.name).join('、') }}<span v-if="commonPrerequisites.length > 12" class="more-count"> …等 {{ commonPrerequisites.length }} 项</span></p>
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

  <LoginModal v-if="showLogin" @close="showLogin = false" />
  <MemberModal v-if="showMember" @close="showMember = false" />

  <Teleport to="body">
    <div v-if="showLearning" class="lc-mask" @click.self="showLearning = false">
      <div class="lc-modal">
        <header class="lc-head">
          <strong>我的学习</strong>
          <button type="button" @click="showLearning = false"><X :size="16" /></button>
        </header>
        <div class="lc-body">
          <section v-for="key in (['want', 'read', 'mastered'] as const)" :key="key" class="lc-group">
            <h4>{{ LEARNING_LABELS[key] }}<small>{{ learningGroups[key].length }}</small></h4>
            <p v-if="!learningGroups[key].length" class="lc-empty">暂无</p>
            <div v-else class="lc-list">
              <div v-for="item in learningGroups[key]" :key="item.id" class="lc-item">
                <button type="button" class="lc-go" @click="openLearningNode(item.id)">
                  <strong>{{ item.name }}</strong>
                  <small>{{ item.era }}{{ item.yearLabel ? ' · ' + item.yearLabel : '' }}</small>
                </button>
                <button type="button" class="lc-rm" title="移出" @click="removeProgress(item.id)"><X :size="13" /></button>
              </div>
            </div>
          </section>
        </div>
      </div>
    </div>
  </Teleport>

  <Teleport to="body">
    <div v-if="showSettings" class="lc-mask" @click.self="showSettings = false">
      <div class="lc-modal settings">
        <header class="lc-head">
          <strong>设置</strong>
          <button type="button" @click="showSettings = false"><X :size="16" /></button>
        </header>
        <div class="lc-body">
          <label class="set-row">
            <span>图谱默认显示边标签</span>
            <input v-model="showEdgeLabels" type="checkbox" />
          </label>
          <button type="button" class="set-clear" @click="clearAllProgress">清空我的学习记录</button>
        </div>
      </div>
    </div>
  </Teleport>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import * as echarts from 'echarts'
import {
  AlertTriangle,
  BrainCircuit,
  GitCompare,
  Layers,
  LoaderCircle,
  MapPinned,
  Maximize2,
  MessageSquareText,
  Minimize2,
  RefreshCcw,
  RotateCcw,
  Route,
  Search,
  SendHorizontal,
  X,
} from '@lucide/vue'
import AppHeader from '../../../app/components/AppHeader.vue'
import GraphPanel from '../components/GraphPanel.vue'
import LoginModal from '../../user/components/LoginModal.vue'
import MemberModal from '../../trade/components/MemberModal.vue'
import {
  fetchSubgraph, fetchOverview, fetchNeighborhood, searchNodes,
  fetchNode, fetchPrerequisites, fetchKnowledgeStatus,
} from '../api'
import type { Tree, NodeBrief, NodeDetail, KnowledgeStatus, Overview, EdgeBrief } from '../types'
import { useUserStore } from '../../user/store'

const ERA_COLORS: Record<number, string> = {
  1: '#ff6b35',
  2: '#596579',
  3: '#0f6da8',
  4: '#1f9a6a',
  5: '#4f8dd3',
  6: '#8a63c7',
  7: '#ed6a3a',
  8: '#2c9f63',
  9: '#e21b5a',
  10: '#0a4c7a',
}
const DEFAULT_VIEW = { zoom: 0.34, center: [0, 0] as [number, number] }
const DEFAULT_EDGE_LABEL = '前置'
const PROGRESS_KEY = 'sparrow_node_progress'
const LEARNING_BRIEF_KEY = 'sparrow_learning_briefs'
const LEARNING_LABELS: Record<'want' | 'read' | 'mastered', string> = { want: '想学', read: '已读', mastered: '已掌握' }
const DIALOG_NODE_LIMIT = 140
const DIALOG_NEIGHBOR_LIMIT = 10

type ProgressState = 'want' | 'read' | 'mastered'
type GraphMode = 'map' | 'dialog'
type DialogExtraction = {
  query: string
  nodes: NodeBrief[]
  edges: EdgeBrief[]
}
type DialogMessage = {
  id: number
  role: 'user' | 'assistant'
  title?: string
  content: string
  terms?: string[]
  nodes?: NodeBrief[]
}
type GraphPoint = {
  x: number
  y: number
  degree: number
}

const user = useUserStore()
const chartRef = ref<HTMLElement | null>(null)
const dialogFeedRef = ref<HTMLElement | null>(null)
const dialogInputRef = ref<HTMLTextAreaElement | null>(null)
let chart: echarts.ECharts | null = null
let latestNodeRequest = 0
let dialogMessageId = 0
let layoutCache: { key: string; positions: Record<number, GraphPoint> } | null = null
let suppressNextGraphClick = false
let graphPan: { pointerId: number; lastX: number; lastY: number; moved: boolean } | null = null
const graphPanning = ref(false)

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
const learningBriefs = ref<Record<number, { name: string; era: string; yearLabel: string }>>({})
const showLearning = ref(false)
const showSettings = ref(false)
const compareNodes = ref<NodeBrief[]>([])
const compareChains = ref<Record<number, NodeBrief[]>>({})
const compareDetails = ref<Record<number, NodeDetail>>({})
const knowledgeStatus = ref<KnowledgeStatus | null>(null)
const graphMode = ref<GraphMode>('map')
const showEdgeLabels = ref(false)
const dialogQuery = ref('')
const dialogLoading = ref(false)
const dialogError = ref('')
const dialogTerms = ref<string[]>([])
const dialogResult = ref<DialogExtraction | null>(null)
const dialogPreviousTree = ref<Tree | null>(null)
const dialogMessages = ref<DialogMessage[]>([])

// 维基级:总览(真实规模 + 领域列表)与有界子图过滤
const overview = ref<Overview | null>(null)
const activeCategory = ref<string | null>(null)
const graphLimit = ref(400)
const LIMIT_OPTIONS = [200, 400, 800]

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

const nodeCount = computed(() => treeData.value?.nodes.length ?? 0)
const edgeCount = computed(() => treeData.value?.edges.length ?? 0)
const hasInformativeEdgeLabels = computed(() => {
  const labels = new Set(
    (treeData.value?.edges ?? [])
      .map(edge => edge.label?.trim())
      .filter((label): label is string => Boolean(label && label !== DEFAULT_EDGE_LABEL)),
  )
  return labels.size > 0
})
const totalNodes = computed(() => overview.value?.totalNodes ?? nodeCount.value)
const totalEdges = computed(() => overview.value?.totalEdges ?? edgeCount.value)
const categories = computed(() => overview.value?.categories ?? [])
const eraCount = computed(() => new Set(treeData.value?.nodes.map(node => node.eraRank) ?? []).size)
const premiumCount = computed(() => treeData.value?.nodes.filter(node => node.premium).length ?? 0)
const nodeById = computed(() => new Map((treeData.value?.nodes ?? []).map(node => [node.id, node])))
const dialogActive = computed(() => Boolean(dialogResult.value))
const dialogNodeIds = computed(() => new Set(dialogResult.value?.nodes.map(node => node.id) ?? []))
const activeEraRank = computed(() => selectedDetail.value?.eraRank ?? selectedPreview.value?.eraRank ?? null)
const currentEraColor = computed(() => activeEraRank.value ? ERA_COLORS[activeEraRank.value] || '#111111' : '#ff5722')
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
  if (dialogActive.value && dialogResult.value) {
    return `临时图谱 · ${dialogResult.value.nodes.length} 节点`
  }
  if (graphMode.value === 'dialog') return '输入问题提取相关节点'
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

// 服务端检索(适配万级规模),带防抖与请求竞态保护
const searchResults = ref<NodeBrief[]>([])
let searchSeq = 0
let searchTimer: ReturnType<typeof setTimeout> | null = null

watch(searchQuery, (value) => {
  activeSearchIndex.value = 0
  if (searchTimer) clearTimeout(searchTimer)
  const query = value.trim()
  if (!query) {
    searchResults.value = []
    return
  }
  searchTimer = setTimeout(async () => {
    const seq = ++searchSeq
    try {
      const results = await searchNodes(query)
      if (seq === searchSeq) searchResults.value = results
    } catch {
      if (seq === searchSeq) searchResults.value = []
    }
  }, 220)
})

watch(showEdgeLabels, () => {
  renderTree()
})

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
    category: detail.category,
    importance: detail.importance,
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
    const rawBrief = localStorage.getItem(LEARNING_BRIEF_KEY)
    learningBriefs.value = rawBrief ? JSON.parse(rawBrief) : {}
  } catch {
    progressMap.value = {}
    learningBriefs.value = {}
  }
}

function saveProgress() {
  localStorage.setItem(PROGRESS_KEY, JSON.stringify(progressMap.value))
  localStorage.setItem(LEARNING_BRIEF_KEY, JSON.stringify(learningBriefs.value))
}

function setCurrentProgress(state: ProgressState) {
  const current = selectedDetail.value ?? selectedPreview.value
  const id = current?.id
  if (!id || !current) return
  progressMap.value = { ...progressMap.value, [id]: state }
  learningBriefs.value = {
    ...learningBriefs.value,
    [id]: { name: current.name, era: current.era, yearLabel: current.yearLabel },
  }
  saveProgress()
}

function removeProgress(id: number) {
  const { [id]: _state, ...restMap } = progressMap.value
  const { [id]: _brief, ...restBrief } = learningBriefs.value
  progressMap.value = restMap
  learningBriefs.value = restBrief
  saveProgress()
}

const learningGroups = computed(() => {
  const groups: Record<'want' | 'read' | 'mastered', Array<{ id: number; name: string; era: string; yearLabel: string }>> = {
    want: [], read: [], mastered: [],
  }
  for (const [idStr, state] of Object.entries(progressMap.value)) {
    const id = Number(idStr)
    const brief = learningBriefs.value[id]
    if (groups[state]) {
      groups[state].push({ id, name: brief?.name || `节点 #${id}`, era: brief?.era || '', yearLabel: brief?.yearLabel || '' })
    }
  }
  return groups
})

function openLearningNode(id: number) {
  showLearning.value = false
  void showNode(id, { focus: true })
}

function clearAllProgress() {
  progressMap.value = {}
  learningBriefs.value = {}
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
  switchGraphMode('dialog')
  void nextTick(() => dialogInputRef.value?.focus())
}

function showMemberModal() {
  if (!user.isLoggedIn()) {
    showLogin.value = true
    return
  }
  showMember.value = true
}

async function scrollDialogFeed() {
  await nextTick()
  if (dialogFeedRef.value) {
    dialogFeedRef.value.scrollTop = dialogFeedRef.value.scrollHeight
  }
}

function pushDialogMessage(message: Omit<DialogMessage, 'id'>) {
  const item = { ...message, id: ++dialogMessageId }
  dialogMessages.value = [...dialogMessages.value, item]
  void scrollDialogFeed()
  return item.id
}

function updateDialogMessage(id: number, patch: Partial<Omit<DialogMessage, 'id'>>) {
  dialogMessages.value = dialogMessages.value.map(message => (
    message.id === id ? { ...message, ...patch } : message
  ))
  void scrollDialogFeed()
}

function switchGraphMode(mode: GraphMode) {
  if (mode === 'map' && dialogActive.value) {
    exitDialogMode()
    return
  }
  graphMode.value = mode
  searchOpen.value = false
  void nextTick(() => {
    chart?.resize()
    if (mode === 'dialog') dialogInputRef.value?.focus()
  })
}

function uniqueItems<T>(items: T[]) {
  return [...new Set(items)]
}

function extractQuestionTerms(text: string) {
  const quoted = [...text.matchAll(/[《「『“"]([^》」』”"]{2,})[》」』”"]/g)].map(match => match[1])
  const stopWords = /(请|帮我|给我|把|将|所有|全部|相关|节点|知识|科技|哪些|哪几|什么|如何|怎么|为什么|介绍|学习|了解|关系|路径|路线|发展|演化|演进|影响|作用|差异|区别|以及|并且|还有|是否|能否|可以|应该|一下|出来|自动|提取|临时|显示|展开|和|与|及|到|从|的|了|是|在|有|吗|呢)/g
  const normalized = text
    .replace(/[，。？！、；：,.?!;:()[\]{}<>《》「」『』“”"']/g, ' ')
    .replace(stopWords, ' ')
  const pieces = normalized.match(/[\u4e00-\u9fffA-Za-z0-9+#.-]{2,}/g) ?? []
  const full = text.replace(/\s+/g, ' ').trim()
  const terms = uniqueItems([
    ...(full.length >= 2 && full.length <= 32 ? [full] : []),
    ...quoted,
    ...pieces,
  ].map(term => term.trim()).filter(term => term.length >= 2 && term.length <= 32))
  return terms.slice(0, 10)
}

function scoreDialogNode(node: NodeBrief, query: string, terms: string[]) {
  const name = node.name || ''
  const summary = node.summary || ''
  const category = node.category || ''
  let score = node.importance ?? 0
  if (query.includes(name)) score += 1000
  for (const term of terms) {
    if (name.includes(term)) score += 180
    if (summary.includes(term)) score += 36
    if (category.includes(term) || node.era.includes(term)) score += 18
  }
  if (node.premium) score += 8
  return score
}

function exitDialogMode() {
  const restored = dialogPreviousTree.value
  dialogResult.value = null
  dialogTerms.value = []
  dialogError.value = ''
  dialogLoading.value = false
  dialogPreviousTree.value = null
  graphMode.value = 'map'

  if (restored) {
    treeData.value = restored
  }

  const selectedId = selectedDetail.value?.id ?? selectedPreview.value?.id
  const selectedStillVisible = selectedId ? treeData.value?.nodes.some(node => node.id === selectedId) : false
  if (!selectedStillVisible) {
    selectedDetail.value = null
    selectedPreview.value = null
    selectedChain.value = []
  }
  highlight.value = null
  renderTree()
  void nextTick(() => chart?.resize())
}

async function runDialogExtraction(queryOverride?: string) {
  const query = (typeof queryOverride === 'string' ? queryOverride : dialogQuery.value).trim()
  if (!query || dialogLoading.value) return
  graphMode.value = 'dialog'
  dialogLoading.value = true
  dialogError.value = ''
  searchOpen.value = false
  dialogQuery.value = ''
  pushDialogMessage({ role: 'user', content: query })
  const assistantMessageId = pushDialogMessage({
    role: 'assistant',
    title: 'BUILDING',
    content: '正在解析问题并构造关联图谱。',
  })

  const terms = extractQuestionTerms(query)
  dialogTerms.value = terms
  if (!terms.length) {
    dialogError.value = '没有识别到可检索的技术词'
    updateDialogMessage(assistantMessageId, {
      title: 'NO MATCH',
      content: dialogError.value,
    })
    dialogLoading.value = false
    return
  }

  const nodeMap = new Map<number, NodeBrief>()
  const edgeMap = new Map<string, EdgeBrief>()
  const addNode = (node?: NodeBrief | null) => {
    if (node) nodeMap.set(node.id, { ...nodeMap.get(node.id), ...node })
  }
  const addEdge = (edge?: EdgeBrief | null) => {
    if (!edge) return
    edgeMap.set(`${edge.from}-${edge.to}`, edge)
  }
  const addTree = (tree?: Tree | null) => {
    for (const node of tree?.nodes ?? []) addNode(node)
    for (const edge of tree?.edges ?? []) addEdge(edge)
  }

  try {
    const batches = await Promise.all(terms.map(async term => {
      const [subgraph, hits] = await Promise.all([
        fetchSubgraph({ q: term, limit: 80 }).catch(() => null),
        searchNodes(term, 30).catch(() => []),
      ])
      return { subgraph, hits }
    }))

    for (const batch of batches) {
      addTree(batch.subgraph)
      for (const hit of batch.hits) addNode(hit)
    }

    const baseTree = dialogPreviousTree.value ?? treeData.value
    if (!nodeMap.size) {
      for (const node of baseTree?.nodes ?? []) {
        if (query.includes(node.name) || terms.some(term => node.name.includes(term) || node.summary.includes(term))) {
          addNode(node)
        }
      }
    }

    const centers = [...nodeMap.values()]
      .sort((a, b) => scoreDialogNode(b, query, terms) - scoreDialogNode(a, query, terms))
      .slice(0, DIALOG_NEIGHBOR_LIMIT)
    const neighborhoods = await Promise.all(centers.map(node => fetchNeighborhood(node.id).catch(() => null)))
    for (const nb of neighborhoods) {
      if (!nb) continue
      addNode(nb.center)
      for (const node of nb.nodes) addNode(node)
      for (const edge of nb.edges) addEdge(edge)
    }

    const nodes = [...nodeMap.values()]
      .sort((a, b) => scoreDialogNode(b, query, terms) - scoreDialogNode(a, query, terms) || a.eraRank - b.eraRank || a.id - b.id)
      .slice(0, DIALOG_NODE_LIMIT)
    if (!nodes.length) {
      dialogError.value = '没有找到匹配的图谱节点'
      updateDialogMessage(assistantMessageId, {
        title: 'NO MATCH',
        content: dialogError.value,
        terms,
      })
      return
    }

    const allowed = new Set(nodes.map(node => node.id))
    const edges = [...edgeMap.values()].filter(edge => allowed.has(edge.from) && allowed.has(edge.to))
    if (!dialogPreviousTree.value && treeData.value) {
      dialogPreviousTree.value = {
        nodes: [...treeData.value.nodes],
        edges: [...treeData.value.edges],
      }
    }

    treeData.value = { nodes, edges }
    dialogResult.value = { query, nodes, edges }
    selectedDetail.value = null
    selectedPreview.value = nodes[0] ?? null
    selectedChain.value = []
    highlight.value = nodes[0]
      ? { selectedId: nodes[0].id, chainIds: new Set(nodes.map(node => node.id)) }
      : null

    await nextTick()
    renderTree()
    if (nodes[0]) centerNode(nodes[0].id, 1.08)
    updateDialogMessage(assistantMessageId, {
      title: 'GRAPH READY',
      content: `已构造 ${nodes.length} 个节点、${edges.length} 条关系的临时图谱。`,
      terms,
      nodes,
    })
  } catch (error: any) {
    dialogError.value = error.message || '临时图谱生成失败'
    updateDialogMessage(assistantMessageId, {
      title: 'ERROR',
      content: dialogError.value,
      terms,
    })
  } finally {
    dialogLoading.value = false
  }
}

function clamp(value: number, min: number, max: number) {
  return Math.min(Math.max(value, min), max)
}

function stableNoise(seed: number, salt: number) {
  const x = Math.sin(seed * 12.9898 + salt * 78.233) * 43758.5453
  return (x - Math.floor(x)) * 2 - 1
}

function layoutNodes(nodes: NodeBrief[], edges: EdgeBrief[] = treeData.value?.edges ?? []) {
  if (!nodes.length) return {}
  const key = `${nodes.map(node => `${node.id}:${node.eraRank}:${node.category ?? ''}`).join('|')}::${edges.map(edge => `${edge.from}-${edge.to}`).join('|')}`
  if (layoutCache?.key === key) return layoutCache.positions

  const nodeIds = new Set(nodes.map(node => node.id))
  const validEdges = edges.filter(edge => nodeIds.has(edge.from) && nodeIds.has(edge.to))
  const degree = new Map(nodes.map(node => [node.id, 0]))
  for (const edge of validEdges) {
    degree.set(edge.from, (degree.get(edge.from) ?? 0) + 1)
    degree.set(edge.to, (degree.get(edge.to) ?? 0) + 1)
  }

  const sortedNodes = [...nodes].sort((a, b) =>
    a.eraRank - b.eraRank ||
    String(a.category ?? '').localeCompare(String(b.category ?? '')) ||
    a.id - b.id,
  )
  const eraRanks = [...new Set(sortedNodes.map(node => node.eraRank))].sort((a, b) => a - b)

  // 时代从左到右排成时间轴;每个时代一个网格块,块内按领域+重要度铺开,确定性不重叠。
  const CELL_X = 72
  const CELL_Y = 50
  const ERA_GAP = 128
  const countByEra = new Map<number, number>()
  for (const node of sortedNodes) countByEra.set(node.eraRank, (countByEra.get(node.eraRank) ?? 0) + 1)
  const eraOffset = new Map<number, number>()
  const eraColsMap = new Map<number, number>()
  const eraRowsMap = new Map<number, number>()
  let eraCursor = 0
  for (const rank of eraRanks) {
    const count = countByEra.get(rank) ?? 1
    const cols = Math.max(1, Math.round(Math.sqrt(count * 0.78)))
    eraOffset.set(rank, eraCursor)
    eraColsMap.set(rank, cols)
    eraRowsMap.set(rank, Math.ceil(count / cols))
    eraCursor += cols * CELL_X + ERA_GAP
  }
  const eraFill = new Map<number, number>()
  const positions: Record<number, GraphPoint> = {}
  for (const node of sortedNodes) {
    const rank = node.eraRank
    const idx = eraFill.get(rank) ?? 0
    eraFill.set(rank, idx + 1)
    const cols = eraColsMap.get(rank) ?? 1
    const rows = eraRowsMap.get(rank) ?? 1
    const col = idx % cols
    const row = Math.floor(idx / cols)
    const xJitter = stableNoise(node.id, 17) * CELL_X * 0.3
    const yJitter = stableNoise(node.id, 41) * CELL_Y * 0.36
    const wave = Math.sin(col * 0.9 + rank * 0.7) * 9
    positions[node.id] = {
      x: Math.round((eraOffset.get(rank) ?? 0) + col * CELL_X + xJitter),
      y: Math.round((row - (rows - 1) / 2) * CELL_Y + yJitter + wave),
      degree: degree.get(node.id) ?? 0,
    }
  }
  layoutCache = { key, positions }
  return positions
}

function buildOption() {
  if (!treeData.value) return {}
  const nodes = treeData.value.nodes
  const edges = treeData.value.edges
  const pos = layoutNodes(nodes, edges)
  const chain = highlight.value ? highlight.value.chainIds : null
  const selected = highlight.value ? highlight.value.selectedId : null
  const learningNodeId = learningCurrentNode.value?.id ?? null
  const learningIds = learningActive.value ? new Set(learningPath.value.nodes.map(node => node.id)) : null
  const isDialogGraph = dialogActive.value
  const tempIds = dialogNodeIds.value
  const showInlineEdgeLabels = showEdgeLabels.value && hasInformativeEdgeLabels.value
  const adjacentIds = new Set<number>()
  if (selected != null) {
    for (const edge of edges) {
      if (edge.from === selected) adjacentIds.add(edge.to)
      if (edge.to === selected) adjacentIds.add(edge.from)
    }
  }

  const data: any[] = nodes.map(node => {
    const point = pos[node.id] ?? { x: 0, y: 0, degree: 0 }
    const inChain = Boolean(chain && (chain.has(node.id) || node.id === selected))
    const adjacent = adjacentIds.has(node.id)
    const dim = (chain || selected != null) && !inChain && !adjacent
    const isSelected = node.id === selected
    const inLearningPath = learningIds?.has(node.id) ?? false
    const isLearningCurrent = node.id === learningNodeId
    const isDialogNode = tempIds.has(node.id)
    const importance = node.importance ?? 0
    const size = clamp(
      5 + Math.min(5, Math.sqrt(point.degree) * 0.9) + Math.min(3.5, importance * 0.035),
      5,
      14,
    )
    const labelAlways = isSelected || adjacent || inChain || inLearningPath || isDialogNode
    const labelVisible = labelAlways || nodes.length <= 160 || (nodes.length <= 540 && (point.degree >= 1 || importance >= 35))
    const color = ERA_COLORS[node.eraRank] || '#111111'

    return {
      id: String(node.id),
      name: node.name,
      x: point.x,
      y: point.y,
      symbol: node.premium ? 'diamond' : 'circle',
      symbolSize: isLearningCurrent ? size + 6 : isSelected ? size + 5 : adjacent ? size + 2.5 : size,
      itemStyle: {
        color,
        opacity: dim ? 0.1 : labelAlways ? 0.98 : 0.88,
        borderColor: isDialogNode ? '#ffffff' : isLearningCurrent ? '#e21b5a' : isSelected ? '#111827' : adjacent || inChain ? '#f43f5e' : 'rgba(255,255,255,0.92)',
        borderWidth: isLearningCurrent ? 2.8 : isSelected ? 2.4 : adjacent || inChain ? 1.8 : 0.8,
        shadowBlur: isLearningCurrent ? 18 : isSelected ? 14 : adjacent || inChain || isDialogNode ? 8 : 0,
        shadowColor: isLearningCurrent || isSelected || adjacent || inChain
          ? 'rgba(226, 27, 90, 0.34)'
          : 'transparent',
      },
      label: {
        show: labelVisible,
        color: dim ? 'rgba(107, 114, 128, 0.2)' : labelAlways ? '#111827' : 'rgba(58, 68, 80, 0.62)',
        fontSize: labelAlways ? 11 : 8,
        fontWeight: isLearningCurrent || isSelected ? 900 : labelAlways ? 760 : 600,
        formatter: `${node.premium ? 'PRO · ' : ''}${node.name}`,
        position: 'right',
        distance: labelAlways ? 6 : 3,
        backgroundColor: labelAlways ? 'rgba(255, 255, 255, 0.88)' : 'transparent',
        borderColor: isSelected || adjacent || inChain ? 'rgba(226, 27, 90, 0.2)' : 'transparent',
        borderWidth: labelAlways ? 1 : 0,
        borderRadius: 4,
        padding: labelAlways ? [2, 5] : 0,
        opacity: dim && !inLearningPath ? 0.18 : 1,
      },
      _nodeId: node.id,
    }
  })

  const edgeData = edges.map(edge => {
    const sourceActive = edge.from === selected || edge.to === selected
    const chainActive = chain && (chain.has(edge.from) || edge.from === selected) && (chain.has(edge.to) || edge.to === selected)
    const learningActiveEdge = Boolean(learningIds?.has(edge.from) && learningIds?.has(edge.to))
    const active = Boolean(sourceActive || chainActive || learningActiveEdge)
    const dim = (chain || selected != null) && !active
    const edgeLabel = edge.label?.trim() || DEFAULT_EDGE_LABEL
    const sourceName = nodeById.value.get(edge.from)?.name ?? String(edge.from)
    const targetName = nodeById.value.get(edge.to)?.name ?? String(edge.to)
    const curveSeed = ((edge.from * 31 + edge.to * 17) % 11) - 5
    return {
      source: String(edge.from),
      target: String(edge.to),
      edgeLabel,
      relationText: `${sourceName} → ${targetName}`,
      label: {
        show: showInlineEdgeLabels && active && edgeLabel !== DEFAULT_EDGE_LABEL,
        formatter: edgeLabel,
        position: 'middle',
        color: active ? '#e21b5a' : '#7a7a7a',
        fontSize: 10,
        backgroundColor: 'rgba(255,255,255,0.9)',
        borderRadius: 3,
        padding: [2, 5],
      },
      lineStyle: {
        color: active ? '#e21b5a' : isDialogGraph ? '#9ea9b5' : '#aeb8c3',
        width: active ? 1.55 : isDialogGraph ? 0.7 : 0.55,
        opacity: dim ? 0.025 : active ? 0.86 : isDialogGraph ? 0.22 : 0.14,
        curveness: curveSeed * (active ? 0.026 : 0.018),
        shadowBlur: active ? 4 : 0,
        shadowColor: 'rgba(226, 27, 90, 0.28)',
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
        if (params.dataType === 'edge') {
          const label = params.data?.edgeLabel || DEFAULT_EDGE_LABEL
          const relation = params.data?.relationText || ''
          return `<strong>${relation}</strong><br/>关系: ${label}`
        }
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
      roam: false,
      zoom: currentView.value.zoom,
      center: currentView.value.center,
      data,
      edges: edgeData,
      edgeSymbol: ['none', 'arrow'],
      edgeSymbolSize: [0, 3],
      edgeLabel: {
        show: false,
        position: 'middle',
        formatter: (params: any) => params.data?.edgeLabel ?? DEFAULT_EDGE_LABEL,
      },
      labelLayout: {
        hideOverlap: nodes.length > 260,
        moveOverlap: 'shiftY',
      },
      animation: nodes.length <= 420,
      animationDurationUpdate: nodes.length > 420 ? 0 : 260,
      animationEasingUpdate: 'cubicOut',
      emphasis: {
        // 移除 focus: 'adjacency'，避免鼠标悬浮时全图虚化
        label: {
          show: true,
          backgroundColor: 'rgba(255, 255, 255, 0.92)',
        },
        itemStyle: {
          shadowBlur: 22,
          shadowColor: 'rgba(255, 87, 34, 0.42)',
        },
        lineStyle: {
          width: 1.7,
          opacity: 0.9,
        },
      },
      silent: false,
    }],
  }
}

function renderTree() {
  const option = buildOption()
  chart?.setOption(option as any, true)
}

/** 数据变更后把视图自动缩放/居中到当前所有节点的范围(看全 + 不偏位)。 */
function fitView() {
  if (!chart || !treeData.value?.nodes.length) return
  const positions = layoutNodes(treeData.value.nodes, treeData.value.edges)
  const pts = Object.values(positions)
  if (!pts.length) return
  const xs = pts.map(p => p.x)
  const ys = pts.map(p => p.y)
  const cx = (Math.min(...xs) + Math.max(...xs)) / 2
  const cy = (Math.min(...ys) + Math.max(...ys)) / 2
  // ECharts graph 的 zoom 是相对"数据 fit 视图"的倍数(zoom≈1 即填满),不是像素比例
  currentView.value = { zoom: 0.92, center: [cx, cy] }
  renderTree()
}

async function loadTree() {
  treeLoading.value = true
  treeError.value = ''
  try {
    // 维基级:不再拉全树,只拉「过滤后按重要度取前 N + 其间的边」的有界子图
    treeData.value = await fetchSubgraph({
      category: activeCategory.value,
      limit: graphLimit.value,
    })
    fitView()
  } catch (error: any) {
    treeError.value = error.message || '无法连接图谱服务'
  } finally {
    treeLoading.value = false
  }
}

async function loadOverview() {
  try {
    overview.value = await fetchOverview()
  } catch {
    overview.value = null
  }
}

function setCategory(cat: string | null) {
  if (dialogActive.value) exitDialogMode()
  activeCategory.value = activeCategory.value === cat ? null : cat
  void loadTree()
}

function setLimit(limit: number) {
  if (graphLimit.value === limit) return
  if (dialogActive.value) exitDialogMode()
  graphLimit.value = limit
  void loadTree()
}

/** 把邻域/详情里的节点与边并入当前显示子图(探索时图谱逐步生长,始终有界)。 */
function mergeSubgraph(nodes: NodeBrief[], edges: EdgeBrief[]) {
  const nodeMap = new Map((treeData.value?.nodes ?? []).map(n => [n.id, n]))
  for (const n of nodes) if (n && !nodeMap.has(n.id)) nodeMap.set(n.id, n)
  const key = (e: EdgeBrief) => `${e.from}-${e.to}`
  const seen = new Set((treeData.value?.edges ?? []).map(key))
  const mergedEdges = [...(treeData.value?.edges ?? [])]
  for (const e of edges) {
    if (!seen.has(key(e))) {
      seen.add(key(e))
      mergedEdges.push(e)
    }
  }
  treeData.value = { nodes: [...nodeMap.values()], edges: mergedEdges }
  if (dialogResult.value) {
    dialogResult.value = {
      ...dialogResult.value,
      nodes: treeData.value.nodes,
      edges: treeData.value.edges,
    }
  }
}

/** 拉取节点邻域并并入显示子图(搜索定位到图外节点时用)。 */
async function expandNode(id: number) {
  try {
    const nb = await fetchNeighborhood(id)
    mergeSubgraph([nb.center, ...nb.nodes], nb.edges)
    renderTree()
  } catch {
    /* 忽略:showNode 仍会加载详情 */
  }
}

async function refreshGraph() {
  const currentDialogQuery = dialogResult.value?.query ?? dialogQuery.value.trim()
  if (graphMode.value === 'dialog' && currentDialogQuery) {
    await runDialogExtraction(currentDialogQuery)
    await loadKnowledgeStatus()
    return
  }
  const selectedId = selectedDetail.value?.id ?? selectedPreview.value?.id
  const keepLearning = learningActive.value
  await loadTree()
  await loadKnowledgeStatus()
  if (selectedId && nodeById.value.has(selectedId)) {
    await showNode(selectedId, { focus: false, fromLearning: keepLearning })
  }
}

function graphViewportCenter(): [number, number] {
  const rect = chartRef.value?.getBoundingClientRect()
  return [
    Math.round((rect?.width || 720) / 2),
    Math.round((rect?.height || 520) / 2),
  ]
}

function defaultGraphView() {
  return {
    zoom: DEFAULT_VIEW.zoom,
    center: graphViewportCenter(),
  }
}

function centerForPoint(point: GraphPoint, _zoom: number): [number, number] {
  // ECharts graph 的 series.center 是数据坐标(视图中心对准的数据点),直接用节点坐标
  return [point.x, point.y]
}

function resetGraphView() {
  fitView()
}

function focusEra(rank: number) {
  if (!treeData.value) return
  const pos = layoutNodes(treeData.value.nodes, treeData.value.edges)
  const eraPoints = treeData.value.nodes
    .filter(node => node.eraRank === rank && pos[node.id])
    .map(node => pos[node.id])
  if (!eraPoints.length) return
  const center = {
    x: eraPoints.reduce((sum, point) => sum + point.x, 0) / eraPoints.length,
    y: eraPoints.reduce((sum, point) => sum + point.y, 0) / eraPoints.length,
    degree: 0,
  }
  currentView.value = {
    zoom: 1.5,
    center: centerForPoint(center, 1.5),
  }
  renderTree()
}

function centerNode(id: number, zoom = 0.92) {
  const node = nodeById.value.get(id)
  if (!node || !treeData.value) return
  const pos = layoutNodes(treeData.value.nodes, treeData.value.edges)[id]
  if (!pos) return
  currentView.value = { zoom, center: centerForPoint(pos, zoom) }
  renderTree()
}

async function toggleGraphFullScreen() {
  graphFullScreen.value = !graphFullScreen.value
  await nextTick()
  chart?.resize()
}

function clearSelection() {
  highlight.value = null
  selectedDetail.value = null
  selectedPreview.value = null
  selectedChain.value = []
  if (learningActive.value) clearLearningPath()
  renderTree()
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
    // 展开式生长:把直接前置/后继并入显示子图(数据已在详情里,零额外请求)
    mergeSubgraph(
      [detailToBrief(detail), ...detail.prerequisites, ...detail.unlocks],
      [
        ...detail.prerequisites.map(p => ({ from: p.id, to: detail.id })),
        ...detail.unlocks.map(u => ({ from: detail.id, to: u.id })),
      ],
    )
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
  if (nodeLoading.value) return
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

async function selectSearchResult(node: NodeBrief) {
  searchQuery.value = node.name
  searchOpen.value = false
  await expandNode(node.id) // 先把该节点邻域并入子图,再聚焦定位
  void showNode(node.id, { focus: true })
}

function clearSearch() {
  searchQuery.value = ''
  searchOpen.value = false
}

/** 全区拖拽平移:用 ECharts graphRoam action(原生 transform,流畅),空白区也响应。 */
function startGraphPan(event: PointerEvent) {
  if ((event.pointerType === 'mouse' && event.button !== 0) || treeLoading.value || treeError.value) return
  // 先不捕获指针;确认是拖拽(移动超阈值)再捕获,否则会拦截 ECharts 的节点点击
  graphPan = { pointerId: event.pointerId, lastX: event.clientX, lastY: event.clientY, moved: false }
}

function moveGraphPan(event: PointerEvent) {
  if (!graphPan || graphPan.pointerId !== event.pointerId) return
  const dx = event.clientX - graphPan.lastX
  const dy = event.clientY - graphPan.lastY
  if (!graphPan.moved && Math.hypot(dx, dy) < 4) return
  if (!graphPan.moved) {
    ;(event.currentTarget as HTMLElement).setPointerCapture(event.pointerId)
  }
  graphPan.moved = true
  graphPanning.value = true
  graphPan.lastX = event.clientX
  graphPan.lastY = event.clientY
  chart?.dispatchAction({ type: 'graphRoam', seriesIndex: 0, dx, dy })
  event.preventDefault()
}

/** 全区滚轮缩放:同样走 graphRoam action,以光标为中心缩放。 */
function onGraphWheel(event: WheelEvent) {
  if (!chart || treeLoading.value || treeError.value) return
  event.preventDefault()
  const rect = chartRef.value?.getBoundingClientRect()
  const factor = event.deltaY < 0 ? 1.12 : 1 / 1.12
  chart.dispatchAction({
    type: 'graphRoam',
    seriesIndex: 0,
    zoom: factor,
    originX: rect ? event.clientX - rect.left : undefined,
    originY: rect ? event.clientY - rect.top : undefined,
  })
}

function stopGraphPan(event: PointerEvent) {
  if (!graphPan || graphPan.pointerId !== event.pointerId) return
  const moved = graphPan.moved
  graphPan = null
  graphPanning.value = false
  const el = event.currentTarget as HTMLElement
  if (el.hasPointerCapture(event.pointerId)) el.releasePointerCapture(event.pointerId)
  if (moved) {
    suppressNextGraphClick = true
    window.setTimeout(() => { suppressNextGraphClick = false }, 120)
  }
}

function cancelGraphPan(event?: PointerEvent) {
  if (event && graphPan?.pointerId !== event.pointerId) return
  graphPan = null
  graphPanning.value = false
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
    chart = echarts.init(chartRef.value, undefined, { renderer: 'canvas' })
    currentView.value = defaultGraphView()
    chart.on('click', (params: any) => {
      if (suppressNextGraphClick) {
        suppressNextGraphClick = false
        return
      }
      if (params.dataType === 'node' && params.data?._nodeId) {
        const clickedId = params.data._nodeId
        const currentId = selectedDetail.value?.id ?? selectedPreview.value?.id
        // 二次点击同一节点 -> 取消选中
        if (clickedId === currentId) {
          clearSelection()
        } else {
          void showNode(clickedId, { focus: false })
        }
      }
    })
    chart.on('graphRoam', updateCurrentViewFromChart)
    window.addEventListener('resize', handleResize)
    window.addEventListener('focus', handleFocus)
    window.addEventListener('keydown', handleKeydown)
  }
  loadProgress()
  await user.loadProfile()
  await Promise.all([loadOverview(), loadTree(), loadKnowledgeStatus()])
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
  background: var(--surface);
  gap: 14px;
  padding: 0 14px 14px;
}

.layout.dialog-layout {
  gap: 8px;
  padding: 0 8px 8px;
  background: #eef1f4;
}

/* ── 左侧控制台 ── */
.side-rail {
  flex: 0 0 244px;
  display: flex;
  flex-direction: column;
  min-height: 0;
  border: 1px solid var(--line);
  border-radius: var(--radius);
  background: var(--panel);
  box-shadow: var(--shadow-sm);
  overflow: hidden;
}

.rail-head {
  padding: 16px 16px 12px;
  border-bottom: 1px solid var(--line);
}

.accent-tag {
  display: inline-flex;
  align-items: center;
  min-height: 20px;
  padding: 0 8px;
  border-radius: 999px;
  background: var(--accent-soft);
  color: var(--accent);
  font-size: 10px;
  font-weight: 800;
  letter-spacing: 0.1em;
}

.rail-head h1 {
  margin-top: 9px;
  font-size: 20px;
  line-height: 1.15;
}

.rail-head p {
  margin-top: 5px;
  color: var(--muted);
  font-size: 12px;
  line-height: 1.6;
}

.rail-stats {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 1px;
  padding: 12px 16px;
  border-bottom: 1px solid var(--line);
}

.rail-stats .stat {
  display: grid;
  gap: 3px;
  padding: 6px 0;
}

.rail-stats strong {
  font-size: 20px;
  line-height: 1;
}

.rail-stats span {
  color: var(--muted);
  font-size: 11px;
  letter-spacing: 0.04em;
}

.rail-scroll {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 4px 0;
}

.graph-workspace {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.dialog-layout .graph-workspace {
  flex: 1 1 auto;
}

.rail-section {
  padding: 12px 16px;
}

.rail-section + .rail-section {
  border-top: 1px solid var(--line);
}

.rail-label {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 10px;
  color: var(--muted);
  font-size: 11px;
  font-weight: 800;
  letter-spacing: 0.1em;
}

.rail-label svg {
  color: var(--accent);
}

.rail-chips {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.rail-chips button {
  min-height: 28px;
  padding: 0 11px;
  border: 1px solid var(--line);
  border-radius: 999px;
  background: var(--surface);
  color: var(--ink-2);
  font-size: 12px;
  cursor: pointer;
  transition: border-color 0.16s ease, color 0.16s ease, background 0.16s ease;
}

.rail-chips button:hover {
  border-color: var(--accent);
  color: var(--accent);
}

.rail-chips button.active {
  border-color: var(--accent);
  background: var(--accent);
  color: var(--bg);
  font-weight: 700;
}

.rail-eras {
  display: grid;
  gap: 3px;
}

.rail-eras button {
  display: grid;
  grid-template-columns: 12px 1fr auto;
  align-items: center;
  gap: 9px;
  min-height: 32px;
  padding: 0 9px;
  border: 1px solid transparent;
  border-radius: var(--radius-sm);
  background: transparent;
  color: var(--ink-2);
  font-size: 12.5px;
  text-align: left;
  cursor: pointer;
  transition: background 0.16s ease, color 0.16s ease;
}

.rail-eras button:hover {
  background: var(--surface);
  color: var(--ink);
}

.rail-eras button.active {
  background: var(--accent-soft);
  color: var(--ink);
}

.rail-eras .era-dot {
  width: 9px;
  height: 9px;
  border-radius: 50%;
  background: var(--era-color, var(--accent));
}

.rail-eras .era-name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.rail-eras small {
  color: var(--muted);
  font-size: 11px;
}

.rail-density {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 6px;
}

.rail-density button {
  min-height: 30px;
  border: 1px solid var(--line);
  border-radius: var(--radius-sm);
  background: var(--surface);
  color: var(--ink-2);
  font-size: 12px;
  cursor: pointer;
  transition: border-color 0.16s ease, color 0.16s ease, background 0.16s ease;
}

.rail-density button:hover {
  border-color: var(--accent);
  color: var(--accent);
}

.rail-density button.active {
  border-color: var(--accent);
  background: var(--accent);
  color: var(--bg);
  font-weight: 700;
}

.graph-shell {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  border: 1px solid var(--line);
  border-radius: var(--radius);
  background: var(--panel);
  box-shadow: var(--shadow-sm);
  overflow: hidden;
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
  background: var(--panel);
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

.mode-switch {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  min-height: 38px;
  padding: 4px;
  border: 1px solid var(--line);
  border-radius: 8px;
  background: var(--surface);
}

.mode-switch button {
  min-width: 58px;
  height: 28px;
  border: 0;
  border-radius: 6px;
  background: transparent;
  color: var(--ink-2);
  font-size: 12px;
  font-weight: 800;
  cursor: pointer;
}

.mode-switch button:hover {
  color: var(--ink);
}

.mode-switch button.active {
  background: var(--panel);
  color: var(--ink);
  box-shadow: var(--shadow-sm);
}

.search-box {
  position: relative;
  flex: 999 1 300px;
  height: 36px;
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
  border: 1px solid var(--line);
  border-radius: 999px;
  background: var(--surface);
  padding: 0 13px;
}

.search-box:focus-within {
  border-color: var(--accent);
  background: var(--panel);
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

.dialog-form {
  position: relative;
  flex: 999 1 360px;
  height: 38px;
  display: flex;
  align-items: center;
  gap: 9px;
  min-width: 0;
  border: 1px solid var(--line-strong);
  border-radius: 999px;
  background: var(--panel);
  padding: 0 7px 0 13px;
}

.dialog-form:focus-within {
  border-color: var(--accent);
  box-shadow: 0 0 0 3px rgba(255, 87, 34, 0.1);
}

.dialog-form svg {
  flex: none;
  color: var(--accent);
}

.dialog-form input {
  flex: 1;
  min-width: 0;
  height: 100%;
  border: 0;
  outline: none;
  background: transparent;
  color: var(--ink);
  font-size: 13px;
}

.dialog-form button {
  display: grid;
  place-items: center;
  width: 28px;
  height: 28px;
  border: 1px solid var(--ink);
  border-radius: 50%;
  background: var(--ink);
  color: var(--bg);
  cursor: pointer;
}

.dialog-form button:disabled {
  border-color: var(--line-strong);
  background: var(--surface);
  color: var(--muted);
  cursor: default;
}

.dialog-mode-status {
  flex: 999 1 360px;
  min-width: 0;
  height: 38px;
  display: inline-flex;
  align-items: center;
  gap: 9px;
  border: 1px solid var(--line);
  border-radius: 999px;
  background: var(--surface);
  color: var(--ink-2);
  padding: 0 14px;
  font-size: 12px;
  font-weight: 800;
}

.dialog-mode-status svg {
  flex: none;
  color: var(--accent);
}

.dialog-mode-status span {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
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
  border: 1px solid var(--line-strong);
  border-radius: var(--radius-sm);
  background: var(--panel);
  box-shadow: var(--shadow-md);
  padding: 4px;
}

.search-results button {
  width: 100%;
  display: grid;
  gap: 3px;
  border: 0;
  border-radius: 6px;
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
  width: 36px;
  height: 36px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border: 1px solid var(--line);
  border-radius: 50%;
  background: var(--surface);
  color: var(--ink-2);
  cursor: pointer;
  transition: border-color 0.16s ease, color 0.16s ease, background 0.16s ease;
}

.tool-btn:hover:not(:disabled) {
  border-color: var(--accent);
  background: var(--accent-soft);
  color: var(--accent);
}

.tool-btn:disabled {
  color: var(--muted);
  cursor: default;
}

.graph-canvas-wrap {
  position: relative;
  flex: 1;
  min-height: 0;
  overflow: hidden;
  background-color: #f8fafb;
  background-image:
    radial-gradient(rgba(82, 94, 111, 0.18) 0.9px, transparent 1px),
    radial-gradient(rgba(226, 27, 90, 0.08) 0.8px, transparent 1px);
  background-position: 0 0, 9px 9px;
  background-size: 18px 18px, 36px 36px;
}

.chart-area {
  position: absolute;
  inset: 0;
  min-width: 0;
  min-height: 360px;
  cursor: grab;
  touch-action: none;
  user-select: none;
}

.chart-area.is-panning {
  cursor: grabbing;
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

.graph-legend {
  position: absolute;
  left: 20px;
  bottom: 20px;
  z-index: 7;
  width: min(520px, calc(100% - 40px));
  border: 1px solid var(--line);
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.92);
  box-shadow: var(--shadow-sm);
  padding: 14px 16px;
  backdrop-filter: blur(10px);
}

.legend-title {
  display: block;
  margin-bottom: 10px;
  color: var(--accent);
  font-size: 11px;
  font-weight: 900;
  letter-spacing: 0.08em;
}

.legend-items {
  display: flex;
  flex-wrap: wrap;
  gap: 10px 14px;
}

.legend-items button {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  max-width: 150px;
  border: 0;
  background: transparent;
  color: var(--ink-2);
  padding: 0;
  font-size: 12px;
  cursor: pointer;
}

.legend-items button:hover,
.legend-items button.active {
  color: var(--ink);
}

.legend-dot {
  flex: none;
  width: 10px;
  height: 10px;
  border-radius: 50%;
  background: var(--era-color);
}

.legend-items span:not(.legend-dot) {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.legend-items small {
  color: var(--muted);
  font-size: 11px;
  font-weight: 800;
}

.edge-label-toggle {
  position: absolute;
  top: 16px;
  right: 18px;
  z-index: 7;
  display: inline-flex;
  align-items: center;
  gap: 10px;
  min-height: 42px;
  border: 1px solid var(--line);
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.94);
  box-shadow: var(--shadow-sm);
  padding: 0 15px 0 12px;
  color: var(--ink-2);
  cursor: pointer;
  backdrop-filter: blur(10px);
}

.edge-label-toggle input {
  position: absolute;
  opacity: 0;
  pointer-events: none;
}

.edge-label-toggle span {
  position: relative;
  width: 46px;
  height: 26px;
  border-radius: 999px;
  background: #d8d8d8;
  transition: background 0.16s ease;
}

.edge-label-toggle span::after {
  content: "";
  position: absolute;
  top: 4px;
  left: 4px;
  width: 18px;
  height: 18px;
  border-radius: 50%;
  background: #ffffff;
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.18);
  transition: transform 0.16s ease;
}

.edge-label-toggle input:checked + span {
  background: var(--accent);
}

.edge-label-toggle input:checked + span::after {
  transform: translateX(20px);
}

.edge-label-toggle strong {
  font-size: 12px;
  font-weight: 800;
}

.dialog-card {
  position: absolute;
  top: 70px;
  right: 18px;
  z-index: 8;
  width: min(360px, calc(100% - 36px));
  max-height: min(470px, calc(100% - 124px));
  display: grid;
  gap: 10px;
  overflow: hidden;
  border: 1px solid var(--line);
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.94);
  box-shadow: var(--shadow-md);
  padding: 14px;
  backdrop-filter: blur(12px);
}

.dialog-card header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}

.dialog-card header div,
.dialog-card header button {
  display: inline-flex;
  align-items: center;
  gap: 7px;
}

.dialog-card header svg {
  color: var(--accent);
}

.dialog-card header strong {
  font-size: 14px;
}

.dialog-card header span {
  color: var(--muted);
  font-size: 11px;
  font-weight: 800;
}

.dialog-card header button {
  min-height: 28px;
  border: 1px solid var(--line-strong);
  border-radius: 6px;
  background: var(--panel);
  color: var(--accent);
  padding: 0 10px;
  font-size: 12px;
  font-weight: 900;
  cursor: pointer;
}

.dialog-query {
  margin: 0;
  color: var(--ink-2);
  font-size: 12px;
  line-height: 1.7;
}

.dialog-terms {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.dialog-terms span {
  border: 1px solid rgba(255, 87, 34, 0.24);
  border-radius: 999px;
  background: rgba(255, 87, 34, 0.08);
  color: var(--accent);
  padding: 4px 8px;
  font-size: 11px;
  font-weight: 800;
}

.dialog-state,
.dialog-error {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  min-height: 32px;
  color: var(--ink-2);
  font-size: 12px;
}

.dialog-error {
  color: var(--danger);
}

.dialog-node-list {
  display: grid;
  gap: 6px;
  overflow-y: auto;
  padding-right: 2px;
}

.dialog-node-list button {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  align-items: center;
  gap: 8px;
  min-height: 34px;
  border: 1px solid var(--line);
  border-radius: 6px;
  background: var(--surface);
  color: var(--ink);
  padding: 0 10px;
  text-align: left;
  cursor: pointer;
}

.dialog-node-list button:hover {
  border-color: var(--accent);
  background: rgba(255, 87, 34, 0.07);
}

.dialog-node-list span {
  overflow: hidden;
  font-size: 12px;
  font-weight: 800;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.dialog-node-list small {
  color: var(--muted);
  font-size: 10px;
  font-weight: 800;
}

.conversation-workbench {
  flex: 0 0 min(440px, 34vw);
  min-width: 360px;
  display: flex;
  flex-direction: column;
  min-height: 0;
  border: 1px solid var(--line);
  border-radius: 8px;
  background: #ffffff;
  box-shadow: var(--shadow-sm);
  overflow: hidden;
}

.workbench-header {
  flex: none;
  min-height: 56px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 0 14px;
  border-bottom: 1px solid var(--line);
}

.workbench-header div {
  min-width: 0;
  display: inline-flex;
  align-items: center;
  gap: 8px;
}

.workbench-header svg {
  flex: none;
  color: var(--accent);
}

.workbench-header strong {
  font-size: 14px;
  letter-spacing: 0.04em;
}

.workbench-header span {
  color: var(--muted);
  font-size: 11px;
  font-weight: 900;
  letter-spacing: 0.08em;
}

.workbench-header button {
  display: grid;
  place-items: center;
  width: 32px;
  height: 32px;
  border: 1px solid var(--line);
  border-radius: 6px;
  background: var(--surface);
  color: var(--ink-2);
  cursor: pointer;
}

.workbench-header button:hover {
  border-color: var(--accent);
  color: var(--accent);
}

.workbench-steps {
  flex: none;
  display: grid;
  grid-template-columns: 1fr 1fr auto;
  gap: 8px;
  align-items: center;
  padding: 12px 14px;
  border-bottom: 1px solid var(--line);
  background: #fbfbfb;
}

.workbench-steps div {
  display: grid;
  gap: 3px;
  border: 1px solid var(--line);
  border-radius: 8px;
  background: #ffffff;
  padding: 8px 10px;
  color: var(--muted);
}

.workbench-steps div.active {
  border-color: rgba(255, 87, 34, 0.28);
  background: rgba(255, 87, 34, 0.07);
  color: var(--ink);
}

.workbench-steps span {
  font-size: 10px;
  font-weight: 900;
  letter-spacing: 0.08em;
}

.workbench-steps strong {
  font-size: 12px;
}

.workbench-steps small {
  width: 9px;
  height: 9px;
  border-radius: 50%;
  background: #c7c7c7;
}

.workbench-steps small.live {
  background: var(--success);
}

.dialog-feed {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  display: grid;
  align-content: start;
  gap: 12px;
  padding: 14px;
  background:
    linear-gradient(#ffffff, rgba(255, 255, 255, 0.94)),
    radial-gradient(#dddddd 1px, transparent 1px);
  background-size: auto, 20px 20px;
}

.dialog-empty {
  min-height: 240px;
  display: grid;
  place-content: center;
  justify-items: center;
  gap: 9px;
  color: var(--muted);
  text-align: center;
}

.dialog-empty svg {
  color: var(--accent);
}

.dialog-empty strong {
  color: var(--ink);
  font-size: 18px;
}

.dialog-empty span {
  font-size: 12px;
}

.dialog-message {
  display: grid;
  gap: 9px;
  border: 1px solid var(--line);
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.96);
  padding: 12px;
  box-shadow: var(--shadow-sm);
}

.dialog-message.user {
  border-color: rgba(17, 24, 39, 0.18);
  background: #f6f7f8;
}

.dialog-message.assistant {
  border-color: rgba(255, 87, 34, 0.2);
}

.message-meta {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.message-meta strong {
  font-size: 11px;
  letter-spacing: 0.08em;
}

.message-meta span {
  color: var(--accent);
  font-size: 10px;
  font-weight: 900;
  letter-spacing: 0.08em;
}

.dialog-message p {
  margin: 0;
  color: var(--ink-2);
  font-size: 13px;
  line-height: 1.7;
  white-space: pre-wrap;
}

.workbench-input {
  flex: none;
  display: grid;
  grid-template-columns: 1fr auto;
  gap: 10px;
  align-items: end;
  padding: 12px;
  border-top: 1px solid var(--line);
  background: #f7f8f9;
}

.workbench-input textarea {
  min-width: 0;
  min-height: 74px;
  max-height: 144px;
  resize: vertical;
  border: 1px solid var(--line);
  border-radius: 8px;
  outline: none;
  background: #ffffff;
  color: var(--ink);
  padding: 10px 12px;
  font: inherit;
  font-size: 13px;
  line-height: 1.6;
}

.workbench-input textarea:focus {
  border-color: var(--accent);
  box-shadow: 0 0 0 3px rgba(255, 87, 34, 0.1);
}

.workbench-input button {
  display: grid;
  place-items: center;
  width: 42px;
  height: 42px;
  border: 0;
  border-radius: 8px;
  background: var(--accent);
  color: #ffffff;
  cursor: pointer;
}

.workbench-input button:disabled {
  background: #d4d8dd;
  cursor: default;
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
  max-height: min(60vh, 560px);
  overflow-y: auto;
  border: 1px solid var(--ink);
  background: var(--panel);
  box-shadow: var(--shadow-md);
}

.compare-result .more-count {
  color: var(--muted);
}

.lc-mask {
  position: fixed;
  inset: 0;
  z-index: 120;
  display: flex;
  align-items: center;
  justify-content: center;
  background: rgba(0, 0, 0, 0.42);
  backdrop-filter: blur(2px);
}

.lc-modal {
  width: min(480px, calc(100vw - 32px));
  max-height: 80vh;
  display: flex;
  flex-direction: column;
  border: 1px solid var(--ink);
  border-radius: var(--radius);
  background: var(--panel);
  box-shadow: var(--shadow-md);
  overflow: hidden;
}

.lc-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 14px 18px;
  border-bottom: 1px solid var(--line);
}

.lc-head strong {
  font-size: 16px;
}

.lc-head button {
  display: grid;
  place-items: center;
  width: 30px;
  height: 30px;
  border: 1px solid var(--line-strong);
  border-radius: var(--radius-sm);
  background: var(--panel);
  color: var(--ink-2);
  cursor: pointer;
}

.lc-head button:hover {
  border-color: var(--ink);
  color: var(--ink);
}

.lc-body {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 14px 18px;
}

.lc-group + .lc-group {
  margin-top: 18px;
}

.lc-group h4 {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 9px;
  font-size: 13px;
  color: var(--ink);
}

.lc-group h4 small {
  color: var(--muted);
  font-weight: 400;
}

.lc-empty {
  color: var(--muted);
  font-size: 12px;
}

.lc-list {
  display: grid;
  gap: 6px;
}

.lc-item {
  display: flex;
  align-items: center;
  gap: 6px;
  border: 1px solid var(--line);
  border-radius: var(--radius-sm);
  background: var(--surface);
}

.lc-item:hover {
  border-color: var(--accent);
}

.lc-go {
  flex: 1;
  min-width: 0;
  display: grid;
  gap: 2px;
  border: 0;
  background: transparent;
  padding: 9px 11px;
  text-align: left;
  cursor: pointer;
}

.lc-go strong {
  overflow: hidden;
  font-size: 13px;
  color: var(--ink);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.lc-go small {
  font-size: 11px;
  color: var(--muted);
}

.lc-rm {
  display: grid;
  place-items: center;
  width: 30px;
  height: 30px;
  border: 0;
  background: transparent;
  color: var(--muted);
  cursor: pointer;
}

.lc-rm:hover {
  color: var(--danger);
}

.set-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  min-height: 40px;
  font-size: 13px;
  color: var(--ink);
}

.set-clear {
  margin-top: 14px;
  width: 100%;
  min-height: 36px;
  border: 1px solid var(--danger);
  border-radius: var(--radius-sm);
  background: transparent;
  color: var(--danger);
  font-size: 13px;
  cursor: pointer;
}

.set-clear:hover {
  background: rgba(220, 38, 38, 0.06);
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

  .mode-switch,
  .dialog-form,
  .dialog-mode-status,
  .search-box {
    flex: 1 1 100%;
  }

  .layout.dialog-layout {
    flex-direction: column;
    overflow: auto;
  }

  .conversation-workbench {
    flex: 0 0 auto;
    width: 100%;
    min-width: 0;
    min-height: 520px;
  }

  .graph-legend {
    left: 12px;
    right: 12px;
    bottom: 12px;
    width: auto;
    max-height: 130px;
    overflow-y: auto;
  }

  .edge-label-toggle {
    top: 12px;
    right: 12px;
  }

  .dialog-card {
    top: 62px;
    left: 12px;
    right: 12px;
    width: auto;
    max-height: 42vh;
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
