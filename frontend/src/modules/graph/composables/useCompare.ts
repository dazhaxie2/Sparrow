import { computed, ref } from 'vue'
import type { NodeBrief, NodeDetail } from '../types'

/** 技术对比:共同前置与分叉方向。 */
export function useCompare() {
  const compareNodes = ref<NodeBrief[]>([])
  const compareChains = ref<Record<number, NodeBrief[]>>({})
  const compareDetails = ref<Record<number, NodeDetail>>({})

  /** 加入对比(最多 2 个,超出则替换较早的一个)。 */
  function addToCompare(node: NodeBrief, chain: NodeBrief[], detail: NodeDetail | null) {
    if (compareNodes.value.some(item => item.id === node.id)) return
    const next = compareNodes.value.length >= 2
      ? [compareNodes.value[1], node]
      : [...compareNodes.value, node]
    compareNodes.value = next
    compareChains.value = { ...compareChains.value, [node.id]: [...chain] }
    if (detail) {
      compareDetails.value = { ...compareDetails.value, [node.id]: detail }
    }
  }

  /** 当已在对比中的节点详情更新时,刷新其前置链与详情。 */
  function refreshCompareEntry(detail: NodeDetail, chain: NodeBrief[]) {
    if (!compareNodes.value.some(item => item.id === detail.id)) return
    compareChains.value = { ...compareChains.value, [detail.id]: chain }
    compareDetails.value = { ...compareDetails.value, [detail.id]: detail }
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

  return {
    compareNodes,
    compareChains,
    compareDetails,
    addToCompare,
    refreshCompareEntry,
    removeCompare,
    clearCompare,
    commonPrerequisites,
    branchSummary,
  }
}
