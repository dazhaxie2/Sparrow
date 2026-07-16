import { computed, nextTick, ref, type Ref } from 'vue'
import { fetchSubgraph, fetchNeighborhood, searchNodes } from '../api'
import type { Tree, NodeBrief, NodeDetail, EdgeBrief } from '../types'
import { askAi, createSession } from '../../../shared/ai/chat'
import type { AiHarnessMetadata } from '../../../shared/ai/harness'

const DIALOG_NODE_LIMIT = 140
const DIALOG_NEIGHBOR_LIMIT = 10

type DialogExtraction = {
  query: string
  nodes: NodeBrief[]
  edges: EdgeBrief[]
}

export type DialogMessage = {
  id: number
  role: 'user' | 'assistant'
  title?: string
  content: string
  harness?: AiHarnessMetadata
}

type HighlightState = { selectedId: number; chainIds: Set<number> } | null

/** 对话模式所需的页面级依赖(图谱数据、选中态与图表回调)。 */
interface DialogDeps {
  treeData: Ref<Tree | null>
  selectedDetail: Ref<NodeDetail | null>
  selectedPreview: Ref<NodeBrief | null>
  selectedChain: Ref<NodeBrief[]>
  highlight: Ref<HighlightState>
  graphMode: Ref<'map' | 'dialog'>
  renderTree: () => void
  centerNode: (id: number, zoom?: number) => void
  resize: () => void
  isLoggedIn: () => boolean
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
  const pieces = normalized.match(/[一-鿿A-Za-z0-9+#.-]{2,}/g) ?? []
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

/** 对话模式:把问题里的技术词抽出来,实时构造一张临时图谱,并交给真 AI 回答。 */
export function useDialogMode(deps: DialogDeps) {
  const dialogLoading = ref(false)
  const dialogError = ref('')
  const dialogResult = ref<DialogExtraction | null>(null)
  const dialogPreviousTree = ref<Tree | null>(null)
  const dialogMessages = ref<DialogMessage[]>([])
  const dialogSessionId = ref<number | null>(null)
  let dialogMessageId = 0

  const dialogActive = computed(() => Boolean(dialogResult.value))
  const dialogNodeIds = computed(() => new Set(dialogResult.value?.nodes.map(node => node.id) ?? []))

  function pushDialogMessage(message: Omit<DialogMessage, 'id'>) {
    const item = { ...message, id: ++dialogMessageId }
    dialogMessages.value = [...dialogMessages.value, item]
    return item.id
  }

  function updateDialogMessage(id: number, patch: Partial<Omit<DialogMessage, 'id'>>) {
    dialogMessages.value = dialogMessages.value.map(message => (
      message.id === id ? { ...message, ...patch } : message
    ))
  }

  function exitDialogMode() {
    const restored = dialogPreviousTree.value
    dialogResult.value = null
    dialogError.value = ''
    dialogLoading.value = false
    dialogPreviousTree.value = null
    deps.graphMode.value = 'map'

    if (restored) {
      deps.treeData.value = restored
    }

    const selectedId = deps.selectedDetail.value?.id ?? deps.selectedPreview.value?.id
    const selectedStillVisible = selectedId ? deps.treeData.value?.nodes.some(node => node.id === selectedId) : false
    if (!selectedStillVisible) {
      deps.selectedDetail.value = null
      deps.selectedPreview.value = null
      deps.selectedChain.value = []
    }
    deps.highlight.value = null
    deps.renderTree()
    void nextTick(() => deps.resize())
  }

  // 对话模式的"回答"由真 AI 给出(/api/ai/ask)。图谱构造仅用于"图谱跟随对话变化"。
  async function replyWithAi(messageId: number, query: string) {
    try {
      if (!deps.isLoggedIn()) {
        updateDialogMessage(messageId, {
          title: '需登录',
          content: '请先登录后再使用 AI 对话，登录后我会结合图谱上下文跟你聊。',
        })
        return
      }
      if (dialogSessionId.value === null) {
        try {
          dialogSessionId.value = (await createSession(query)).sessionId
        } catch {
          // 会话创建是记忆增强；失败时仍允许无会话问答，Harness 会明确 contextMessages=0。
        }
      }
      const res = await askAi(query, 'graph-dialog', dialogSessionId.value)
      updateDialogMessage(messageId, { title: '', content: res.answer, harness: res.harness })
    } catch (error: any) {
      updateDialogMessage(messageId, {
        title: '出错',
        content: `抱歉，AI 暂时不可用${error.message ? '（' + error.message + '）' : ''}，请稍后再试。`,
      })
    }
  }

  async function runDialogExtraction(rawQuery: string) {
    const query = rawQuery.trim()
    if (!query || dialogLoading.value) return
    deps.graphMode.value = 'dialog'
    dialogLoading.value = true
    dialogError.value = ''
    pushDialogMessage({ role: 'user', content: query })
    const assistantMessageId = pushDialogMessage({
      role: 'assistant',
      title: '',
      content: '正在思考…',
    })

    const terms = extractQuestionTerms(query)
    if (!terms.length) {
      // 没有可检索的技术词(如"你好"):不改图谱,直接给 AI 对话回答
      await replyWithAi(assistantMessageId, query)
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

      const baseTree = dialogPreviousTree.value ?? deps.treeData.value
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
        // 图谱没命中相关节点:不改图谱,仍给出 AI 对话回答
        await replyWithAi(assistantMessageId, query)
        return
      }

      const allowed = new Set(nodes.map(node => node.id))
      const edges = [...edgeMap.values()].filter(edge => allowed.has(edge.from) && allowed.has(edge.to))
      if (!dialogPreviousTree.value && deps.treeData.value) {
        dialogPreviousTree.value = {
          nodes: [...deps.treeData.value.nodes],
          edges: [...deps.treeData.value.edges],
        }
      }

      deps.treeData.value = { nodes, edges }
      dialogResult.value = { query, nodes, edges }
      deps.selectedDetail.value = null
      deps.selectedPreview.value = nodes[0] ?? null
      deps.selectedChain.value = []
      deps.highlight.value = nodes[0]
        ? { selectedId: nodes[0].id, chainIds: new Set(nodes.map(node => node.id)) }
        : null

      await nextTick()
      deps.renderTree()
      if (nodes[0]) deps.centerNode(nodes[0].id, 1.08)
      // 图谱已随对话更新;回答交给真 AI
      await replyWithAi(assistantMessageId, query)
    } catch {
      // 图谱构造失败也不打断对话,直接给 AI 回答
      await replyWithAi(assistantMessageId, query)
    } finally {
      dialogLoading.value = false
    }
  }

  return {
    dialogLoading,
    dialogError,
    dialogResult,
    dialogMessages,
    dialogActive,
    dialogNodeIds,
    runDialogExtraction,
    exitDialogMode,
  }
}
