<template>
  <div class="graph-canvas-wrap">
    <div ref="chartAreaRef" class="chart-area"></div>

    <div v-if="treeLoading" class="graph-overlay">
      <LoaderCircle class="spin" :size="22" />
      <strong>正在加载知识图谱</strong>
      <span>整理节点与关系结构</span>
    </div>

    <div v-else-if="treeError" class="graph-overlay error">
      <AlertTriangle :size="22" />
      <strong>图谱加载失败</strong>
      <span>{{ treeError }}</span>
      <button type="button" @click="$emit('retry')">重试</button>
    </div>

    <div v-if="categoryLegend.length && !treeLoading && !treeError && !immersive" class="graph-legend" aria-label="领域图例">
      <span class="legend-title">CATEGORY TYPES</span>
      <div class="legend-items">
        <button
          v-for="category in categoryLegend"
          :key="category.name"
          type="button"
          :style="{ '--category-color': category.color }"
          @click="$emit('focusCategory', category.name)"
        >
          <span class="legend-dot"></span>
          <span>{{ category.name }}</span>
          <small>{{ category.count }}</small>
        </button>
      </div>
    </div>

    <label v-if="hasInformativeEdgeLabels && !treeLoading && !treeError && !immersive" class="edge-label-toggle">
      <input
        type="checkbox"
        :checked="showEdgeLabels"
        @change="$emit('update:showEdgeLabels', ($event.target as HTMLInputElement).checked)"
      />
      <span></span>
      <strong>关系名称</strong>
    </label>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { AlertTriangle, LoaderCircle } from '@lucide/vue'
import type { CategoryLegendItem } from '../composables/graphOption'

defineProps<{
  treeLoading: boolean
  treeError: string
  categoryLegend: CategoryLegendItem[]
  hasInformativeEdgeLabels: boolean
  showEdgeLabels: boolean
  /** 全屏沉浸模式:隐藏图例与关系名开关等画布叠层。 */
  immersive?: boolean
}>()

defineEmits<{
  retry: []
  focusCategory: [category: string]
  'update:showEdgeLabels': [value: boolean]
}>()

const chartAreaRef = ref<HTMLElement | null>(null)
defineExpose({ getChartEl: () => chartAreaRef.value })
</script>

<style scoped>
.graph-canvas-wrap {
  position: relative;
  flex: 1;
  min-height: 0;
  overflow: hidden;
  background-color: #fbfbfa;
  background-image: radial-gradient(rgba(108, 116, 124, 0.2) 0.8px, transparent 0.8px);
  background-size: 20px 20px;
}

.graph-canvas-wrap::after {
  content: "";
  position: absolute;
  inset: 0;
  pointer-events: none;
  background: linear-gradient(to bottom, rgba(255,255,255,0.2), transparent 12%, transparent 88%, rgba(255,255,255,0.24));
}

.chart-area {
  position: absolute;
  inset: 0;
  z-index: 1;
  min-width: 0;
  min-height: 360px;
  cursor: grab;
  touch-action: none;
  user-select: none;
}

.chart-area:active {
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
  background: rgba(255, 255, 255, 0.84);
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
  left: 18px;
  bottom: 18px;
  z-index: 7;
  width: min(520px, calc(100% - 40px));
  border: 1px solid var(--line);
  max-height: 100px;
  overflow-y: auto;
  border-radius: 9px;
  background: rgba(255, 255, 255, 0.95);
  box-shadow: 0 8px 24px rgba(20, 24, 29, 0.08);
  padding: 11px 13px;
  backdrop-filter: blur(14px);
}

.legend-title {
  display: block;
  margin-bottom: 8px;
  color: var(--accent);
  font-size: 9px;
  font-weight: 900;
  letter-spacing: 0.1em;
}

.legend-items {
  display: flex;
  flex-wrap: wrap;
  gap: 7px 13px;
}

.legend-items button {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  max-width: 160px;
  border: 0;
  background: transparent;
  color: var(--ink-2);
  padding: 0;
  font-size: 10px;
  cursor: pointer;
}

.legend-items button:hover {
  color: var(--ink);
}

.legend-dot {
  flex: none;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--category-color);
}

.legend-items span:not(.legend-dot) {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.legend-items small {
  color: var(--muted);
  font-size: 9px;
  font-weight: 800;
}

.edge-label-toggle {
  position: absolute;
  right: 18px;
  bottom: 18px;
  z-index: 7;
  display: inline-flex;
  align-items: center;
  gap: 9px;
  min-height: 32px;
  border: 1px solid var(--line);
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.95);
  box-shadow: var(--shadow-sm);
  padding: 0 11px 0 8px;
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
  width: 32px;
  height: 18px;
  border-radius: 999px;
  background: #d8d8d8;
  transition: background 0.16s ease;
}

.edge-label-toggle span::after {
  content: "";
  position: absolute;
  top: 3px;
  left: 3px;
  width: 12px;
  height: 12px;
  border-radius: 50%;
  background: #fff;
  box-shadow: 0 1px 3px rgba(0,0,0,0.2);
  transition: transform 0.16s ease;
}

.edge-label-toggle input:checked + span {
  background: var(--accent);
}

.edge-label-toggle input:checked + span::after {
  transform: translateX(14px);
}

.edge-label-toggle strong {
  font-size: 11px;
}

.spin {
  animation: spin 0.9s linear infinite;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

@media (max-width: 720px) {
  .graph-legend {
    left: 10px;
    bottom: 10px;
    width: calc(100% - 24px);
    max-height: 82px;
    overflow-y: auto;
  }

  .edge-label-toggle {
    right: 10px;
    bottom: 100px;
  }
}
</style>
