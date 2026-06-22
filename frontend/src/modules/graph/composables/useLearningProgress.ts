import { computed, ref } from 'vue'

export type ProgressState = 'want' | 'read' | 'mastered'

const PROGRESS_KEY = 'sparrow_node_progress'
const LEARNING_BRIEF_KEY = 'sparrow_learning_briefs'

export const LEARNING_LABELS: Record<ProgressState, string> = {
  want: '想学',
  read: '已读',
  mastered: '已掌握',
}

type Brief = { name: string; era: string; yearLabel: string }

/** 学习进度(想学/已读/已掌握)与本地持久化。 */
export function useLearningProgress() {
  const progressMap = ref<Record<number, ProgressState>>({})
  const learningBriefs = ref<Record<number, Brief>>({})

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

  function setProgress(node: { id: number; name: string; era: string; yearLabel: string }, state: ProgressState) {
    progressMap.value = { ...progressMap.value, [node.id]: state }
    learningBriefs.value = {
      ...learningBriefs.value,
      [node.id]: { name: node.name, era: node.era, yearLabel: node.yearLabel },
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

  function clearAllProgress() {
    progressMap.value = {}
    learningBriefs.value = {}
    saveProgress()
  }

  const progressCounts = computed(() => {
    const values = Object.values(progressMap.value)
    return {
      want: values.filter(value => value === 'want').length,
      read: values.filter(value => value === 'read').length,
      mastered: values.filter(value => value === 'mastered').length,
    }
  })

  const learningGroups = computed(() => {
    const groups: Record<ProgressState, Array<{ id: number; name: string; era: string; yearLabel: string }>> = {
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

  return {
    progressMap,
    learningBriefs,
    loadProgress,
    setProgress,
    removeProgress,
    clearAllProgress,
    progressCounts,
    learningGroups,
  }
}
