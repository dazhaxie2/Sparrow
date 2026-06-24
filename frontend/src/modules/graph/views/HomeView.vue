<template>
  <AppHeader
    :graph-mode="graphMode"
    @show-graph="switchGraphMode('map')"
    @open-login="showLogin = true"
    @open-member="showMemberModal"
    @open-learning="showLearning = true"
    @open-settings="showSettings = true"
  />

  <main class="layout" :class="{ 'dialog-layout': graphMode === 'dialog' }">
    <GraphSideRail
      v-if="graphMode === 'map'"
      :total-nodes="totalNodes"
      :total-edges="totalEdges"
      :mastered-count="progressCounts.mastered"
      :premium-count="premiumCount"
      :categories="categories"
      :active-category="activeCategory"
      :limit-options="LIMIT_OPTIONS"
      :graph-limit="graphLimit"
      @set-category="setCategory"
      @set-limit="setLimit"
    />

    <section class="graph-workspace">
      <section class="graph-shell" :class="{ fullscreen: graphFullScreen, 'dialog-mode': dialogActive }" aria-label="科技图谱">
        <GraphToolbar
          :graph-mode="graphMode"
          :dialog-active="dialogActive"
          :node-count="nodeCount"
          :total-nodes="totalNodes"
          :display-mode="displayMode"
          :represented-node-count="representedNodeCount"
          :cluster-drilldown="Boolean(drilledCluster)"
          :drilldown-label="drilledCluster?.name ?? ''"
          :dialog-query="dialogResult?.query ?? ''"
          :tree-loading="treeLoading"
          :graph-full-screen="graphFullScreen"
          :selected-status-text="selectedStatusText"
          @refresh="refreshGraph"
          @exit-cluster="exitClusterDrilldown"
          @toggle-full-screen="toggleGraphFullScreen"
          @select="onSearchSelect"
        />

        <GraphCanvas
          ref="canvasRef"
          :tree-loading="treeLoading"
          :tree-error="treeError"
          :category-legend="categoryLegend"
          :has-informative-edge-labels="hasInformativeEdgeLabels"
          :show-edge-labels="showEdgeLabels"
          @retry="loadTree"
          @focus-category="focusCategory"
          @update:show-edge-labels="value => (showEdgeLabels = value)"
        />
      </section>
    </section>

    <GraphPanel
      v-if="graphMode === 'map' && (selectedDetail || selectedPreview || nodeLoading || nodeError)"
      :detail="selectedDetail"
      :preview="selectedPreview"
      :loading="nodeLoading"
      :error="nodeError"
      :progress="currentProgress"
      :progress-map="progressMap"
      :accent-color="currentNodeColor"
      :applications="currentApplications"
      :applications-loading="applicationsLoading"
      :floating="true"
      @select="selectFromPanel"
      @retry="retrySelectedNode"
      @set-progress="setCurrentProgress"
      @add-compare="addCurrentToCompare"
      @open-member="showMemberModal"
    />

    <DialogWorkbench
      v-if="graphMode === 'dialog'"
      :dialog-messages="dialogMessages"
      :dialog-loading="dialogLoading"
      :dialog-error="dialogError"
      :dialog-active="dialogActive"
      @submit="runDialogExtraction"
      @switch-to-map="switchGraphMode('map')"
    />

    <CompareDock
      v-if="compareNodes.length"
      :compare-nodes="compareNodes"
      :common-prerequisites="commonPrerequisites"
      :branch-summary="branchSummary"
      @remove="removeCompare"
      @clear="clearCompare"
    />
  </main>

  <LoginModal v-if="showLogin" @close="showLogin = false" />
  <MemberModal v-if="showMember" @close="showMember = false" />

  <GraphModals
    :show-learning="showLearning"
    :show-settings="showSettings"
    :learning-groups="learningGroups"
    :show-edge-labels="showEdgeLabels"
    @update:show-learning="value => (showLearning = value)"
    @update:show-settings="value => (showSettings = value)"
    @update:show-edge-labels="value => (showEdgeLabels = value)"
    @open-learning-node="openLearningNode"
    @remove-progress="removeProgress"
    @clear-all-progress="clearAllProgress"
  />
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import AppHeader from '../../../app/components/AppHeader.vue'
import GraphPanel from '../components/GraphPanel.vue'
import GraphSideRail from '../components/GraphSideRail.vue'
import GraphToolbar from '../components/GraphToolbar.vue'
import GraphCanvas from '../components/GraphCanvas.vue'
import DialogWorkbench from '../components/DialogWorkbench.vue'
import CompareDock from '../components/CompareDock.vue'
import GraphModals from '../components/GraphModals.vue'
import LoginModal from '../../user/components/LoginModal.vue'
import MemberModal from '../../trade/components/MemberModal.vue'
import { fetchClusterOverview, fetchSubgraph, fetchTile, fetchOverview, fetchNeighborhood, fetchNode, fetchPrerequisites, fetchApplications } from '../api'
import type { Tree, NodeBrief, NodeDetail, Overview, EdgeBrief, GraphTile, ClusterOverview, ClusterNode } from '../types'
import { useUserStore } from '../../user/store'
import { colorForCategory, createCategoryLegend } from '../composables/graphOption'
import { useSigmaGraph as useGraphChart } from '../composables/useSigmaGraph'
import { useDialogMode } from '../composables/useDialogMode'
import { useLearningProgress, type ProgressState } from '../composables/useLearningProgress'
import { useCompare } from '../composables/useCompare'

type GraphMode = 'map' | 'dialog'
type DisplayMode = 'raw' | 'community' | 'lod'

const LIMIT_OPTIONS = [200, 400, 800]
const RAW_NODE_LIMIT = 1000
const COMMUNITY_NODE_LIMIT = 10000

const user = useUserStore()
const route = useRoute()
const router = useRouter()

// ── 页面级状态 ──
const treeData = ref<Tree | null>(null)
const selectedDetail = ref<NodeDetail | null>(null)
const selectedPreview = ref<NodeBrief | null>(null)
const selectedChain = ref<NodeBrief[]>([])
const highlight = ref<{ selectedId: number; chainIds: Set<number> } | null>(null)
// 「应用与产业链」:材料/化合物类节点按需懒计算(AI 判定 + 缓存),非材料节点恒为空。
const applications = ref<NodeBrief[]>([])
const applicationsLoading = ref(false)
// 记录已计算过应用的节点 id,避免同一节点并发重复请求;null 表示当前无 in-flight。
let applicationsRequestId = 0
const overview = ref<Overview | null>(null)
const clusterOverview = ref<ClusterOverview | null>(null)
const drilledCluster = ref<{ id: number; name: string; size: number } | null>(null)
const activeCategory = ref<string | null>(null)
const graphLimit = ref(800)
const graphMode = ref<GraphMode>('map')
const showEdgeLabels = ref(false)

const treeLoading = ref(false)
const treeError = ref('')
const nodeLoading = ref(false)
const nodeError = ref('')
const showLogin = ref(false)
const showMember = ref(false)
const graphFullScreen = ref(false)
const showLearning = ref(false)
const showSettings = ref(false)

let latestNodeRequest = 0
let latestTreeRequest = 0
const expandedTileClusters = new Set<number>()
const loadedLodLevels = new Map<number, number>()
let clusterOverviewPromise: Promise<ClusterOverview> | null = null

// ── 逻辑 composable ──
const { progressMap, loadProgress, setProgress, removeProgress, clearAllProgress, progressCounts, learningGroups } = useLearningProgress()
const { compareNodes, addToCompare, refreshCompareEntry, removeCompare, clearCompare, commonPrerequisites, branchSummary } = useCompare()

// ── 派生状态(图表/对话之前定义,供其依赖) ──
const nodeCount = computed(() => treeData.value?.nodes.length ?? 0)
const displayMode = computed<DisplayMode>(() => graphLimit.value <= RAW_NODE_LIMIT
  ? 'raw'
  : graphLimit.value <= COMMUNITY_NODE_LIMIT
    ? 'community'
    : 'lod')
const representedNodeCount = computed(() => {
  const nodes = treeData.value?.nodes ?? []
  const representedClusters = new Set(nodes
    .filter(node => (node.clusterSize ?? 0) > 1 && node.clusterId != null)
    .map(node => node.clusterId as number))
  return nodes.reduce((sum, node) => {
    if ((node.clusterSize ?? 0) > 1) return sum + (node.clusterSize ?? 1)
    if (node.clusterId != null && representedClusters.has(node.clusterId)) return sum
    return sum + 1
  }, 0)
})
const edgeCount = computed(() => treeData.value?.edges.length ?? 0)
const hasInformativeEdgeLabels = computed(() => {
  const labels = new Set(
    (treeData.value?.edges ?? [])
      .map(edge => edge.label?.trim())
      .filter((label): label is string => Boolean(label && label !== '前置')),
  )
  return labels.size > 0
})
const totalNodes = computed(() => overview.value?.totalNodes ?? nodeCount.value)
const totalEdges = computed(() => overview.value?.totalEdges ?? edgeCount.value)
const categories = computed(() => overview.value?.categories ?? [])
const premiumCount = computed(() => treeData.value?.nodes.filter(node => node.premium).length ?? 0)
const nodeById = computed(() => new Map((treeData.value?.nodes ?? []).map(node => [node.id, node])))
const currentProgress = computed(() => {
  const id = selectedDetail.value?.id ?? selectedPreview.value?.id
  return id ? progressMap.value[id] ?? null : null
})

const categoryLegend = computed(() => createCategoryLegend(treeData.value?.nodes ?? [], categories.value))
const currentNodeColor = computed(() => {
  const category = selectedDetail.value?.category ?? selectedPreview.value?.category
  return colorForCategory(category, categoryLegend.value)
})

// 仅当详情/预览属于材料类时,把当前 applications 透传给面板;否则恒为空(不显示区块)。
const MATERIAL_CATEGORIES = new Set(['材料冶金', '化学化工'])
const currentApplications = computed<NodeBrief[]>(() => {
  const category = selectedDetail.value?.category ?? selectedPreview.value?.category
  if (!category || !MATERIAL_CATEGORIES.has(category)) return []
  return applications.value
})

const canvasRef = ref<{ getChartEl: () => HTMLElement | null } | null>(null)

// 图表与对话存在双向依赖:图表渲染需读对话状态,对话构造需调图表渲染。
// 先建图表(对话状态用 dialogApi 惰性读取),再建对话(注入图表方法),最后回填 dialogApi。
let dialogApi: ReturnType<typeof useDialogMode> | null = null

const graphChart = useGraphChart({
  getTree: () => treeData.value,
  getRenderState: () => ({
    highlight: highlight.value,
    dialogActive: dialogApi?.dialogActive.value ?? false,
    dialogNodeIds: dialogApi?.dialogNodeIds.value ?? new Set<number>(),
    showInlineEdgeLabels: showEdgeLabels.value && hasInformativeEdgeLabels.value,
    nodeById: nodeById.value,
    categoryOrder: categories.value,
  }),
  onNodeClick: handleNodeClick,
  onViewChange: handleGraphViewChange,
  isBusy: () => treeLoading.value || Boolean(treeError.value),
})

const {
  renderTree,
  fitView,
  centerNode,
  focusCategory,
} = graphChart

const dialog = useDialogMode({
  treeData,
  selectedDetail,
  selectedPreview,
  selectedChain,
  highlight,
  graphMode,
  renderTree,
  centerNode,
  resize: graphChart.resize,
  isLoggedIn: () => user.isLoggedIn(),
})
dialogApi = dialog

const { dialogLoading, dialogError, dialogResult, dialogMessages, dialogActive, runDialogExtraction, exitDialogMode } = dialog

const selectedStatusText = computed(() => {
  if (dialogActive.value && dialogResult.value) {
    return `临时图谱 · ${dialogResult.value.nodes.length} 节点`
  }
  if (graphMode.value === 'dialog') return '输入问题提取相关节点'
  if (selectedDetail.value) return selectedDetail.value.name
  if (selectedPreview.value) return selectedPreview.value.name
  return '等待选择节点'
})

// ── 工具函数 ──
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

function tileToTree(tile: GraphTile): Tree {
  return {
    nodes: tile.nodes.map(node => ({
      id: node.id,
      code: `tile-${node.id}`,
      name: node.name || `#${node.id}`,
      era: '',
      eraRank: 0,
      yearLabel: '',
      summary: '',
      premium: false,
      category: node.category,
      importance: node.importance,
      x: node.x,
      y: node.y,
      clusterId: node.clusterId,
      lodLevel: tile.level,
    })),
    edges: tile.edges,
  }
}

function clusterNodesToTree(clusters: ClusterNode[]): Tree {
  return {
    nodes: clusters.map(cluster => ({
      id: cluster.id,
      code: `cluster-${cluster.clusterId}`,
      name: cluster.name || `社区 ${cluster.clusterId}`,
      era: '',
      eraRank: 0,
      yearLabel: '',
      summary: `该社区包含 ${cluster.nodeCount} 个节点，点击可展开。`,
      premium: false,
      category: cluster.category,
      importance: cluster.importance,
      x: cluster.x,
      y: cluster.y,
      clusterId: cluster.clusterId,
      lodLevel: 0,
      clusterSize: cluster.nodeCount,
    })),
    edges: [],
  }
}

async function getClusterOverview() {
  if (clusterOverview.value) return clusterOverview.value
  if (!clusterOverviewPromise) {
    clusterOverviewPromise = fetchClusterOverview()
      .then(data => {
        clusterOverview.value = data
        return data
      })
      .finally(() => {
        clusterOverviewPromise = null
      })
  }
  return clusterOverviewPromise
}

function clustersForTarget(data: ClusterOverview, target: number, category: string | null) {
  const candidates = category
    ? data.clusters.filter(cluster => cluster.category === category)
    : data.clusters
  if (displayMode.value === 'lod') return candidates

  const selected: ClusterNode[] = []
  let represented = 0
  for (const cluster of candidates) {
    selected.push(cluster)
    represented += cluster.nodeCount
    if (represented >= target) break
  }
  return selected
}

function highlightIdsFor(chain: NodeBrief[]) {
  return new Set(chain.map(node => node.id))
}

function nodeHighlight(selectedId: number, chain: NodeBrief[]) {
  return { selectedId, chainIds: highlightIdsFor(chain) }
}

function setCurrentProgress(state: ProgressState) {
  const current = selectedDetail.value ?? selectedPreview.value
  if (!current) return
  setProgress({ id: current.id, name: current.name, era: current.era, yearLabel: current.yearLabel }, state)
}

function openLearningNode(id: number) {
  showLearning.value = false
  void showNode(id, { focus: true })
}

function addCurrentToCompare() {
  const current = selectedDetail.value ? detailToBrief(selectedDetail.value) : selectedPreview.value
  if (!current) return
  addToCompare(current, selectedChain.value, selectedDetail.value)
}

function openAiGuide() {
  switchGraphMode('dialog')
}

function showMemberModal() {
  if (!user.isLoggedIn()) {
    showLogin.value = true
    return
  }
  showMember.value = true
}

function consumeHeaderIntent() {
  const intent = Array.isArray(route.query.open) ? route.query.open[0] : route.query.open
  if (!intent) return

  if (intent === 'login') showLogin.value = true
  else if (intent === 'member') showMemberModal()
  else if (intent === 'learning') showLearning.value = true
  else if (intent === 'settings') showSettings.value = true

  const nextQuery = { ...route.query }
  delete nextQuery.open
  void router.replace({ path: '/', query: nextQuery })
}

function switchGraphMode(mode: GraphMode) {
  if (mode === 'map' && dialogActive.value) {
    exitDialogMode()
    return
  }
  graphMode.value = mode
  void nextTick(() => graphChart.resize())
}

/** 把邻域/详情里的节点与边并入当前显示子图(探索时图谱逐步生长,始终有界)。 */
function mergeSubgraph(nodes: NodeBrief[], edges: EdgeBrief[]) {
  const map = new Map((treeData.value?.nodes ?? []).map(n => [n.id, n]))
  for (const n of nodes) {
    if (!n) continue
    map.set(n.id, { ...map.get(n.id), ...n })
  }
  const key = (e: EdgeBrief) => `${e.from}-${e.to}`
  const seen = new Set((treeData.value?.edges ?? []).map(key))
  const mergedEdges = [...(treeData.value?.edges ?? [])]
  for (const e of edges) {
    if (!seen.has(key(e))) {
      seen.add(key(e))
      mergedEdges.push(e)
    }
  }
  treeData.value = { nodes: [...map.values()], edges: mergedEdges }
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

async function loadTree() {
  const requestId = ++latestTreeRequest
  treeLoading.value = true
  treeError.value = ''
  try {
    drilledCluster.value = null
    let nextTree: Tree
    if (displayMode.value === 'raw') {
      nextTree = await fetchSubgraph({
        category: activeCategory.value,
        limit: graphLimit.value,
      })
    } else {
      const clusters = await getClusterOverview()
      nextTree = clusterNodesToTree(clustersForTarget(
        clusters,
        graphLimit.value,
        activeCategory.value,
      ))
    }
    if (requestId !== latestTreeRequest) return
    const selectedId = selectedDetail.value?.id ?? selectedPreview.value?.id
    if (selectedId != null && !nextTree.nodes.some(node => node.id === selectedId)) {
      highlight.value = null
      selectedDetail.value = null
      selectedPreview.value = null
      selectedChain.value = []
      applications.value = []
    }
    treeData.value = nextTree
    expandedTileClusters.clear()
    loadedLodLevels.clear()
    renderTree()
    requestAnimationFrame(() => fitView())
  } catch (error: any) {
    if (requestId !== latestTreeRequest) return
    treeError.value = error.message || '无法连接图谱服务'
  } finally {
    if (requestId === latestTreeRequest) treeLoading.value = false
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

async function refreshGraph() {
  const currentDialogQuery = dialogResult.value?.query ?? ''
  if (graphMode.value === 'dialog' && currentDialogQuery) {
    await runDialogExtraction(currentDialogQuery)
    return
  }
  const selectedId = selectedDetail.value?.id ?? selectedPreview.value?.id
  if (displayMode.value !== 'raw') clusterOverview.value = null
  await loadTree()
  if (selectedId && nodeById.value.has(selectedId)) {
    await showNode(selectedId, { focus: false })
  }
}

async function toggleGraphFullScreen() {
  if (!graphFullScreen.value) {
    // 优先使用浏览器原生全屏:可隐藏浏览器/系统外壳并支持 ESC 退出。
    // 在受限 iframe 等不支持/被拒的环境下,回退到 CSS 伪全屏(仅最大化页面内的图谱区)。
    try {
      if (document.documentElement.requestFullscreen) {
        await document.documentElement.requestFullscreen()
      }
    } catch {
      /* 原生全屏被拒,继续走 CSS 伪全屏 */
    }
    graphFullScreen.value = true
  } else {
    if (document.fullscreenElement) {
      try {
        await document.exitFullscreen()
      } catch {
        /* 忽略,下面照常关闭 CSS 伪全屏 */
      }
    }
    graphFullScreen.value = false
  }
  await nextTick()
  graphChart.resize()
}

/** 同步原生全屏状态:用户按 ESC 退出时,一并撤掉 CSS 最大化并刷新图表。 */
function handleFullscreenChange() {
  if (!document.fullscreenElement && graphFullScreen.value) {
    graphFullScreen.value = false
    nextTick(() => graphChart.resize())
  }
}

function clearSelection() {
  highlight.value = null
  selectedDetail.value = null
  selectedPreview.value = null
  selectedChain.value = []
  applications.value = []
  renderTree()
}

async function showNode(id: number, options: { focus?: boolean } = {}) {
  if (!treeData.value) return
  const requestId = ++latestNodeRequest
  const preview = nodeById.value.get(id) ?? null
  selectedPreview.value = preview
  selectedDetail.value = null
  selectedChain.value = []
  applications.value = []
  nodeLoading.value = true
  nodeError.value = ''
  if (options.focus) centerNode(id)

  try {
    // 百万级节点可能走 MySQL 降级路径，顺序读取可避免同一节点详情/路径并发争抢连接。
    const detail = await fetchNode(id)
    const chain = await fetchPrerequisites(id)
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
    refreshCompareEntry(detail, chain)
    renderTree()
    // 合并详情邻域会触发重新布局，使用最终坐标再次定位；邻域接口失败时也能兜底聚焦。
    if (options.focus) requestAnimationFrame(() => centerNode(id))
    // 材料类节点按需懒计算「应用与产业链」(AI 判定 + 服务端缓存)。
    void loadApplications(id)
  } catch (error: any) {
    if (requestId !== latestNodeRequest) return
    nodeError.value = error.message || '节点详情加载失败'
    highlight.value = nodeHighlight(id, [])
    renderTree()
    if (options.focus) requestAnimationFrame(() => centerNode(id))
  } finally {
    if (requestId === latestNodeRequest) nodeLoading.value = false
  }
}

/** 按需加载节点「应用与产业链」:仅材料类节点触发,服务端命中缓存秒出,未命中时 AI 判定。 */
async function loadApplications(id: number) {
  const detail = selectedDetail.value
  const category = detail?.category
  if (!category || !MATERIAL_CATEGORIES.has(category)) return
  const requestId = ++applicationsRequestId
  applicationsLoading.value = true
  try {
    const list = await fetchApplications(id)
    if (requestId !== applicationsRequestId) return
    applications.value = list
  } catch {
    if (requestId !== applicationsRequestId) return
    applications.value = []
  } finally {
    if (requestId === applicationsRequestId) applicationsLoading.value = false
  }
}

async function handleGraphViewChange(view: { zoom: number; centerClusterId: number | null }) {
  if (displayMode.value !== 'lod' || view.centerClusterId == null || treeLoading.value) return
  const level = view.zoom >= 4 ? 3 : view.zoom >= 2.4 ? 2 : view.zoom >= 1.45 ? 1 : 0
  if (level === 0) return

  const clusterId = view.centerClusterId
  const loadedLevel = loadedLodLevels.get(clusterId) ?? 0
  if (loadedLevel >= level) return
  loadedLodLevels.set(clusterId, level)
  const treeRequestId = latestTreeRequest

  try {
    const tile = tileToTree(await fetchTile(level, clusterId))
    if (displayMode.value !== 'lod' || treeRequestId !== latestTreeRequest) return
    mergeSubgraph(tile.nodes, tile.edges)
    renderTree()
  } catch {
    if (loadedLodLevels.get(clusterId) === level) loadedLodLevels.set(clusterId, loadedLevel)
  }
}

async function handleNodeClick(id: number) {
  const currentId = selectedDetail.value?.id ?? selectedPreview.value?.id
  // 二次点击同一节点 -> 取消选中
  if (id === currentId) {
    clearSelection()
  } else {
    const node = nodeById.value.get(id)
    if (node && (node.clusterSize ?? 0) > 1 && node.clusterId != null) {
      await openClusterDrilldown(node, node.clusterId)
      return
    }
    await showNode(id, { focus: false })
  }
}

async function openClusterDrilldown(node: NodeBrief, clusterId: number) {
  treeLoading.value = true
  treeError.value = ''
  try {
    const cluster = tileToTree(await fetchTile(3, clusterId))
    highlight.value = null
    selectedDetail.value = null
    selectedPreview.value = null
    selectedChain.value = []
    applications.value = []
    treeData.value = cluster
    drilledCluster.value = {
      id: clusterId,
      name: node.category || node.name,
      size: cluster.nodes.length,
    }
    expandedTileClusters.add(clusterId)
    loadedLodLevels.set(clusterId, 3)
    renderTree()
    requestAnimationFrame(() => fitView())
  } catch (error: any) {
    treeError.value = error.message || '社区节点加载失败'
  } finally {
    treeLoading.value = false
  }
}

async function exitClusterDrilldown() {
  if (!drilledCluster.value) return
  await loadTree()
}

function retrySelectedNode() {
  const id = selectedPreview.value?.id ?? selectedDetail.value?.id
  if (id) void showNode(id, { focus: false })
}

function selectFromPanel(id: number) {
  void showNode(id, { focus: true })
}

async function onSearchSelect(node: NodeBrief) {
  await expandNode(node.id) // 先把该节点邻域并入子图,再聚焦定位
  void showNode(node.id, { focus: true })
}

function handleResize() {
  graphChart.resize()
}

function handleFocus() {
  user.loadProfile()
}

watch(showEdgeLabels, () => renderTree())

onMounted(async () => {
  loadProgress()
  const el = canvasRef.value?.getChartEl()
  if (el) graphChart.init(el)
  window.addEventListener('resize', handleResize)
  window.addEventListener('focus', handleFocus)
  document.addEventListener('fullscreenchange', handleFullscreenChange)
  await user.loadProfile()
  consumeHeaderIntent()
  await Promise.all([loadOverview(), loadTree()])
})

onUnmounted(() => {
  window.removeEventListener('resize', handleResize)
  window.removeEventListener('focus', handleFocus)
  document.removeEventListener('fullscreenchange', handleFullscreenChange)
  graphChart.dispose()
})
</script>

<style scoped>
.layout {
  position: relative;
  display: flex;
  height: calc(100vh - 52px);
  min-height: 0;
  background: #fbfbfa;
  overflow: hidden;
}

.layout.dialog-layout {
  background: #eef1f4;
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

.graph-shell {
  position: relative;
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  background: #fbfbfa;
  overflow: hidden;
}

.graph-shell.fullscreen {
  position: fixed;
  inset: 52px 0 0;
  z-index: 60;
}

@media (max-width: 920px) {
  .layout {
    height: calc(100vh - 48px);
  }

  .graph-workspace {
    min-height: 0;
    overflow: hidden;
  }

  .graph-shell.fullscreen {
    inset: 48px 0 0;
  }

  .layout.dialog-layout {
    overflow: hidden;
  }
}
</style>
