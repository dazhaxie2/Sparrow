<template>
  <div class="graph-toolbar">
    <div class="toolbar-title">
      <MapPinned :size="16" />
      <strong>{{ dialogActive ? '会话临时图谱' : '知识图谱' }}</strong>
      <span>{{ dialogActive ? `提取 ${nodeCount} 个相关节点` : `显示 ${nodeCount} / ${totalNodes}` }}</span>
    </div>

    <div class="mode-switch" aria-label="图谱模式">
      <button type="button" :class="{ active: graphMode === 'map' }" @click="$emit('switchMode', 'map')">
        图谱
      </button>
      <button type="button" :class="{ active: graphMode === 'dialog' }" @click="$emit('switchMode', 'dialog')">
        对话
      </button>
    </div>

    <div
      v-if="graphMode === 'map'"
      class="search-box"
      @keydown.down.prevent="moveSearch(1)"
      @keydown.up.prevent="moveSearch(-1)"
    >
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
          @mousedown.prevent="selectResult(node)"
        >
          <span>{{ node.name }}</span>
          <small>{{ node.era }} · {{ node.yearLabel }}</small>
        </button>
      </div>
    </div>

    <div v-else class="dialog-mode-status">
      <BrainCircuit :size="15" />
      <span>{{ dialogQuery || '关联节点实时构造' }}</span>
    </div>

    <div class="toolbar-actions" aria-label="图谱控制">
      <span class="selected-status">{{ selectedStatusText }}</span>
      <button class="tool-btn" type="button" title="刷新图谱" :disabled="treeLoading" @click="$emit('refresh')">
        <RefreshCcw :size="15" />
      </button>
      <button class="tool-btn" type="button" title="重置视图" @click="$emit('reset')">
        <RotateCcw :size="16" />
      </button>
      <button
        class="tool-btn"
        type="button"
        :title="graphFullScreen ? '退出全屏' : '全屏查看'"
        @click="$emit('toggleFullScreen')"
      >
        <Minimize2 v-if="graphFullScreen" :size="16" />
        <Maximize2 v-else :size="16" />
      </button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, watch } from 'vue'
import {
  BrainCircuit,
  MapPinned,
  Maximize2,
  Minimize2,
  RefreshCcw,
  RotateCcw,
  Search,
  X,
} from '@lucide/vue'
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
  switchMode: [mode: 'map' | 'dialog']
  refresh: []
  reset: []
  toggleFullScreen: []
  select: [node: NodeBrief]
}>()

// 搜索子系统:服务端检索(适配万级规模),带防抖与请求竞态保护
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

.tool-btn.active {
  border-color: var(--accent);
  background: var(--accent-soft);
  color: var(--accent);
}

@media (max-width: 1180px) {
  .toolbar-actions {
    justify-content: flex-start;
  }
}

@media (max-width: 920px) {
  .mode-switch,
  .dialog-mode-status {
    flex: 1 1 100%;
  }
}

@media (max-width: 600px) {
  .graph-toolbar {
    gap: 8px;
    padding: 8px;
  }

  .toolbar-title {
    flex: 1 1 150px;
  }

  .toolbar-title span,
  .selected-status {
    display: none;
  }

  .mode-switch {
    flex: none;
  }

  .search-box {
    flex: 1 1 190px;
  }

  .toolbar-actions {
    flex: 0 0 auto;
    gap: 6px;
  }

  .tool-btn {
    width: 34px;
    height: 34px;
  }
}
</style>
