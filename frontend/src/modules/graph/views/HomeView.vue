<template>
  <AppHeader
    :graph-mode="graphMode"
    @show-graph="switchGraphMode('map')"
    @open-login="showLogin = true"
    @open-member="showMemberModal"
    @focus-ai="openAiGuide"
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
          :dialog-query="dialogResult?.query ?? ''"
          :tree-loading="treeLoading"
          :graph-full-screen="graphFullScreen"
          :selected-status-text="selectedStatusText"
          @refresh="refreshGraph"
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
      :path-nodes="pathNodes"
      :learning-active="learningActive"
      :learning-index="learningIndex"
      :learning-total="learningTotal"
      :loading="nodeLoading"
      :error="nodeError"
      :progress="currentProgress"
      :accent-color="currentNodeColor"
      :floating="true"
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
import { fetchSubgraph, fetchTile, fetchOverview, fetchNeighborhood, fetchNode, fetchPrerequisites } from '../api'
import type { Tree, NodeBrief, NodeDetail, Overview, EdgeBrief, GraphTile } from '../types'
import { useUserStore } from '../../user/store'
import { colorForCategory, createCategoryLegend } from '../composables/graphOption'
import { useSigmaGraph as useGraphChart } from '../composables/useSigmaGraph'
import { useDialogMode } from '../composables/useDialogMode'
import { useLearningProgress, type ProgressState } from '../composables/useLearningProgress'
import { useCompare } from '../composables/useCompare'

type GraphMode = 'map' | 'dialog'

const LIMIT_OPTIONS = [200, 400, 800]

const user = useUserStore()

// ── 页面级状态 ──
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
const overview = ref<Overview | null>(null)
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
const expandedTileClusters = new Set<number>()

// ── 逻辑 composable ──
const { progressMap, loadProgress, setProgress, removeProgress, clearAllProgress, progressCounts, learningGroups } = useLearningProgress()
const { compareNodes, addToCompare, refreshCompareEntry, removeCompare, clearCompare, commonPrerequisites, branchSummary } = useCompare()

// ── 派生状态(图表/对话之前定义,供其依赖) ──
const nodeCount = computed(() => treeData.value?.nodes.length ?? 0)
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

const categoryLegend = computed(() => createCategoryLegend(treeData.value?.nodes ?? [], categories.value))
const currentNodeColor = computed(() => {
  const category = selectedDetail.value?.category ?? selectedPreview.value?.category
  return colorForCategory(category, categoryLegend.value)
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

const canvasRef = ref<{ getChartEl: () => HTMLElement | null } | null>(null)

// 图表与对话存在双向依赖:图表渲染需读对话状态,对话构造需调图表渲染。
// 先建图表(对话状态用 dialogApi 惰性读取),再建对话(注入图表方法),最后回填 dialogApi。
let dialogApi: ReturnType<typeof useDialogMode> | null = null

const graphChart = useGraphChart({
  getTree: () => treeData.value,
  getRenderState: () => ({
    highlight: highlight.value,
    learningActive: learningActive.value,
    learningNodes: learningPath.value.nodes,
    learningCurrentId: learningCurrentNode.value?.id ?? null,
    dialogActive: dialogApi?.dialogActive.value ?? false,
    dialogNodeIds: dialogApi?.dialogNodeIds.value ?? new Set<number>(),
    showInlineEdgeLabels: showEdgeLabels.value && hasInformativeEdgeLabels.value,
    nodeById: nodeById.value,
    categoryOrder: categories.value,
  }),
  onNodeClick: handleNodeClick,
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
  if (learningActive.value && learningCurrentNode.value) {
    return `路径第 ${learningIndex.value + 1} 步 · ${learningCurrentNode.value.name}`
  }
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
  treeLoading.value = true
  treeError.value = ''
  try {
    // 维基级:不再拉全树,只拉「过滤后按重要度取前 N + 其间的边」的有界子图
    if (activeCategory.value) {
      treeData.value = await fetchSubgraph({
        category: activeCategory.value,
        limit: graphLimit.value,
      })
    } else {
      treeData.value = tileToTree(await fetchTile(0, 0))
    }
    expandedTileClusters.clear()
    renderTree()
    requestAnimationFrame(() => fitView())
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

async function refreshGraph() {
  const currentDialogQuery = dialogResult.value?.query ?? ''
  if (graphMode.value === 'dialog' && currentDialogQuery) {
    await runDialogExtraction(currentDialogQuery)
    return
  }
  const selectedId = selectedDetail.value?.id ?? selectedPreview.value?.id
  const keepLearning = learningActive.value
  await loadTree()
  if (selectedId && nodeById.value.has(selectedId)) {
    await showNode(selectedId, { focus: false, fromLearning: keepLearning })
  }
}

async function toggleGraphFullScreen() {
  graphFullScreen.value = !graphFullScreen.value
  await nextTick()
  graphChart.resize()
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
    refreshCompareEntry(detail, chain)
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

async function handleNodeClick(id: number) {
  const currentId = selectedDetail.value?.id ?? selectedPreview.value?.id
  // 二次点击同一节点 -> 取消选中
  if (id === currentId) {
    clearSelection()
  } else {
    const node = nodeById.value.get(id)
    const clusterId = node?.clusterId
    if (node?.lodLevel === 0 && clusterId != null && !expandedTileClusters.has(clusterId)) {
      expandedTileClusters.add(clusterId)
      try {
        const cluster = tileToTree(await fetchTile(3, clusterId))
        mergeSubgraph(cluster.nodes, cluster.edges)
        renderTree()
      } catch {
        expandedTileClusters.delete(clusterId)
      }
    }
    await showNode(id, { focus: false })
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

watch(showEdgeLabels, () => renderTree())

onMounted(async () => {
  loadProgress()
  const el = canvasRef.value?.getChartEl()
  if (el) graphChart.init(el)
  window.addEventListener('resize', handleResize)
  window.addEventListener('focus', handleFocus)
  window.addEventListener('keydown', handleKeydown)
  await user.loadProfile()
  await Promise.all([loadOverview(), loadTree()])
})

onUnmounted(() => {
  window.removeEventListener('resize', handleResize)
  window.removeEventListener('focus', handleFocus)
  window.removeEventListener('keydown', handleKeydown)
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
