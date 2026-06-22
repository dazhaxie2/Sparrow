<template>
  <div class="graph-toolbar">
    <div class="toolbar-title">
      <strong>{{ dialogActive ? '对话临时图谱' : 'Graph Relationship Visualization' }}</strong>
      <span>{{ dialogActive ? `提取 ${nodeCount} 个相关节点` : `${nodeCount} nodes · ${totalNodes} total` }}</span>
    </div>

    <div
      v-if="graphMode === 'map'"
      class="search-box"
      @keydown.down.prevent="moveSearch(1)"
      @keydown.up.prevent="moveSearch(-1)"
    >
      <Search :size="14" />
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
          @mousedown.prevent="selectResult(node)"
        >
          <span>{{ node.name }}</span>
          <small>{{ node.era }} · {{ node.yearLabel }}</small>
        </button>
      </div>
    </div>

    <div v-else class="dialog-mode-status">
      <BrainCircuit :size="14" />
      <span>{{ dialogQuery || '关联节点实时构建' }}</span>
    </div>

    <div class="toolbar-actions" aria-label="图谱控制">
      <span class="selected-status">{{ selectedStatusText }}</span>
      <button class="tool-btn text-btn" type="button" title="刷新图谱" :disabled="treeLoading" @click="$emit('refresh')">
        <RefreshCcw :size="14" />
        <span>Refresh</span>
      </button>
      <button
        class="tool-btn"
        type="button"
        :title="graphFullScreen ? '退出全屏' : '全屏查看'"
        @click="$emit('toggleFullScreen')"
      >
        <Minimize2 v-if="graphFullScreen" :size="14" />
        <Maximize2 v-else :size="14" />
      </button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, watch } from 'vue'
import { BrainCircuit, Maximize2, Minimize2, RefreshCcw, Search, X } from '@lucide/vue'
import { searchNodes } from '../api'
import type { NodeBrief } from '../types'

defineProps<{
  graphMode: 'map' | 'dialog'
  dialogActive: boolean
  nodeCount: number
  totalNodes: number
  dialogQuery: string
  treeLoading: boolean
  graphFullScreen: boolean
  selectedStatusText: string
}>()

const emit = defineEmits<{
  refresh: []
  toggleFullScreen: []
  select: [node: NodeBrief]
}>()

const searchQuery = ref('')
const searchOpen = ref(false)
const activeSearchIndex = ref(0)
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

function moveSearch(delta: number) {
  if (!searchResults.value.length) return
  searchOpen.value = true
  activeSearchIndex.value = (activeSearchIndex.value + delta + searchResults.value.length) % searchResults.value.length
}

function confirmSearch() {
  const node = searchResults.value[activeSearchIndex.value] ?? searchResults.value[0]
  if (node) selectResult(node)
}

function selectResult(node: NodeBrief) {
  searchQuery.value = node.name
  searchOpen.value = false
  emit('select', node)
}

function clearSearch() {
  searchQuery.value = ''
  searchOpen.value = false
}
</script>

<style scoped>
.graph-toolbar {
  position: absolute;
  inset: 14px 16px auto;
  z-index: 20;
  min-height: 36px;
  display: grid;
  grid-template-columns: minmax(210px, 1fr) auto minmax(210px, 1fr);
  align-items: start;
  gap: 12px;
  pointer-events: none;
}

.toolbar-title {
  display: grid;
  gap: 3px;
  padding-top: 6px;
  color: var(--ink);
}

.toolbar-title strong {
  font-size: 12px;
  letter-spacing: 0.01em;
}

.toolbar-title span {
  color: var(--muted);
  font-size: 9px;
  letter-spacing: 0.04em;
}

.search-box,
.dialog-mode-status,
.toolbar-actions {
  pointer-events: auto;
}

.search-box,
.dialog-mode-status {
  position: absolute;
  top: 0;
  left: 50%;
  width: min(330px, 36vw);
  height: 34px;
  display: flex;
  align-items: center;
  gap: 7px;
  transform: translateX(-50%);
  border: 1px solid rgba(20, 24, 29, 0.1);
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.94);
  box-shadow: 0 5px 18px rgba(20, 24, 29, 0.06);
  padding: 0 11px;
  backdrop-filter: blur(12px);
}

.search-box:focus-within {
  border-color: rgba(255, 87, 34, 0.45);
}

.search-box svg,
.dialog-mode-status svg {
  flex: none;
  color: var(--muted);
}

.search-box input {
  flex: 1;
  min-width: 0;
  height: 100%;
  border: 0;
  outline: 0;
  background: transparent;
  color: var(--ink);
  font-size: 11px;
}

.clear-search {
  width: 22px;
  height: 22px;
  display: grid;
  place-items: center;
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
  z-index: 40;
  border: 1px solid var(--line);
  border-radius: 8px;
  background: #fff;
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
  padding: 8px 9px;
  text-align: left;
  cursor: pointer;
}

.search-results button:hover,
.search-results button.active {
  background: var(--accent-soft);
}

.search-results span {
  font-size: 12px;
  font-weight: 800;
}

.search-results small {
  color: var(--muted);
  font-size: 9px;
}

.dialog-mode-status {
  color: var(--ink-2);
  font-size: 10px;
}

.toolbar-actions {
  grid-column: 3;
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 7px;
}

.selected-status {
  display: none;
}

.tool-btn {
  min-width: 34px;
  height: 34px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  border: 1px solid rgba(20, 24, 29, 0.1);
  border-radius: 7px;
  background: rgba(255, 255, 255, 0.94);
  box-shadow: 0 5px 18px rgba(20, 24, 29, 0.06);
  color: #60666d;
  padding: 0 9px;
  font-size: 10px;
  cursor: pointer;
  backdrop-filter: blur(12px);
}

.tool-btn:hover:not(:disabled) {
  border-color: rgba(255, 87, 34, 0.38);
  color: var(--accent);
}

.tool-btn:disabled {
  opacity: 0.5;
  cursor: default;
}

@media (max-width: 760px) {
  .graph-toolbar {
    inset: 10px 10px auto;
    grid-template-columns: 1fr auto;
  }

  .toolbar-title span,
  .text-btn span {
    display: none;
  }

  .toolbar-title strong {
    max-width: 150px;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .search-box,
  .dialog-mode-status {
    top: 43px;
    left: 0;
    width: min(280px, calc(100vw - 92px));
    transform: none;
  }

  .toolbar-actions {
    grid-column: 2;
  }
}
</style>
