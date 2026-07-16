<template>
  <aside class="side-rail" :class="{ open: railOpen }" aria-label="知识图谱筛选">
    <button class="rail-trigger" type="button" :aria-expanded="railOpen" @click="railOpen = !railOpen">
      <SlidersHorizontal :size="14" />
      <span>筛选</span>
      <small>{{ graphLimit }}</small>
    </button>

    <div v-if="railOpen" class="rail-popover">
      <div class="rail-head">
        <div>
          <span>GRAPH CONTROLS</span>
          <strong>图谱筛选</strong>
        </div>
        <button type="button" title="关闭筛选" @click="railOpen = false"><X :size="15" /></button>
      </div>

      <div class="rail-stats" aria-label="图谱指标">
        <div><strong>{{ totalNodes }}</strong><span>节点</span></div>
        <div><strong>{{ totalEdges }}</strong><span>关系</span></div>
        <div><strong>{{ favoriteCount }}</strong><span>收藏</span></div>
        <div><strong>{{ premiumCount }}</strong><span>深度</span></div>
      </div>

      <section v-if="categories.length" class="rail-section">
        <div class="rail-label"><Layers :size="13" /><span>领域</span></div>
        <div class="rail-chips">
          <button type="button" :class="{ active: activeCategory === null }" @click="$emit('setCategory', null)">全部</button>
          <button
            v-for="cat in categories"
            :key="cat"
            type="button"
            :class="{ active: activeCategory === cat }"
            @click="$emit('setCategory', cat)"
          >{{ cat }}</button>
        </div>
      </section>

      <section class="rail-section density-section">
        <div class="rail-label"><span>节点数量</span></div>
        <div class="rail-density">
          <button
            v-for="opt in limitOptions"
            :key="opt"
            type="button"
            :class="{ active: graphLimit === opt }"
            @click="selectLimit(opt)"
          >{{ opt }}</button>
          <input
            v-model="customLimit"
            class="rail-custom-limit"
            :class="{ active: !limitOptions.includes(graphLimit) }"
            type="number"
            inputmode="numeric"
            :min="1"
            step="1"
            placeholder="自定义"
            aria-label="自定义节点数量，停止输入后自动加载"
            @input="scheduleCustomLimit"
            @keydown.enter.prevent="applyCustomLimit"
            @blur="applyCustomLimit"
          />
        </div>
      </section>
    </div>
  </aside>
</template>

<script setup lang="ts">
import { onUnmounted, ref, watch } from 'vue'
import { Layers, SlidersHorizontal, X } from '@lucide/vue'

const props = defineProps<{
  totalNodes: number
  totalEdges: number
  favoriteCount: number
  premiumCount: number
  categories: string[]
  activeCategory: string | null
  limitOptions: number[]
  graphLimit: number
}>()

const emit = defineEmits<{
  setCategory: [cat: string | null]
  setLimit: [limit: number]
}>()

const railOpen = ref(false)
const customLimit = ref<number | string>(props.graphLimit)
const CUSTOM_LIMIT_DEBOUNCE_MS = 600
let pendingCustomTimer: ReturnType<typeof setTimeout> | null = null
let lastRequestedLimit = props.graphLimit

watch(() => props.graphLimit, value => {
  customLimit.value = value
  lastRequestedLimit = value
})

function clearCustomTimer() {
  if (pendingCustomTimer == null) return
  clearTimeout(pendingCustomTimer)
  pendingCustomTimer = null
}

function normalizedCustomLimit() {
  if (String(customLimit.value).trim() === '') return null
  const parsed = Number(customLimit.value)
  if (!Number.isFinite(parsed)) return null
  return Math.min(Number.MAX_SAFE_INTEGER, Math.max(1, Math.round(parsed)))
}

function selectLimit(limit: number) {
  clearCustomTimer()
  customLimit.value = limit
  if (limit === lastRequestedLimit) return
  lastRequestedLimit = limit
  emit('setLimit', limit)
}

function applyCustomLimit() {
  clearCustomTimer()
  const normalized = normalizedCustomLimit()
  if (normalized == null) {
    customLimit.value = props.graphLimit
    return
  }
  customLimit.value = normalized
  if (normalized === lastRequestedLimit) return
  lastRequestedLimit = normalized
  emit('setLimit', normalized)
}

function scheduleCustomLimit() {
  clearCustomTimer()
  if (normalizedCustomLimit() == null) return
  pendingCustomTimer = setTimeout(applyCustomLimit, CUSTOM_LIMIT_DEBOUNCE_MS)
}

onUnmounted(clearCustomTimer)
</script>

<style scoped>
.side-rail {
  position: absolute;
  top: 68px;
  left: 18px;
  z-index: 30;
  pointer-events: none;
}

.rail-trigger {
  pointer-events: auto;
  min-height: 34px;
  display: inline-flex;
  align-items: center;
  gap: 7px;
  border: 1px solid rgba(20, 24, 29, 0.1);
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.94);
  box-shadow: 0 5px 18px rgba(20, 24, 29, 0.07);
  padding: 0 11px;
  color: #4c535a;
  font-size: 12px;
  cursor: pointer;
  backdrop-filter: blur(12px);
}

.rail-trigger:hover,
.side-rail.open .rail-trigger {
  color: var(--accent);
  border-color: rgba(255, 87, 34, 0.32);
}

.rail-trigger small {
  min-width: 27px;
  border-left: 1px solid var(--line);
  padding-left: 7px;
  color: var(--muted);
  font-size: 10px;
  text-align: right;
}

.rail-popover {
  pointer-events: auto;
  width: min(360px, calc(100vw - 36px));
  max-height: min(610px, calc(100vh - 132px));
  margin-top: 8px;
  overflow-y: auto;
  border: 1px solid rgba(20, 24, 29, 0.1);
  border-radius: 10px;
  background: rgba(255, 255, 255, 0.97);
  box-shadow: 0 18px 50px rgba(20, 24, 29, 0.13);
  backdrop-filter: blur(18px);
}

.rail-head {
  min-height: 58px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
  padding: 10px 14px;
  border-bottom: 1px solid var(--line);
}

.rail-head > div {
  display: grid;
  gap: 2px;
}

.rail-head span {
  color: var(--accent);
  font-size: 9px;
  font-weight: 900;
  letter-spacing: 0.12em;
}

.rail-head strong {
  font-size: 14px;
}

.rail-head button {
  width: 28px;
  height: 28px;
  display: grid;
  place-items: center;
  border: 0;
  border-radius: 6px;
  background: transparent;
  color: var(--muted);
  cursor: pointer;
}

.rail-head button:hover {
  background: var(--surface-2);
  color: var(--ink);
}

.rail-stats {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  border-bottom: 1px solid var(--line);
}

.rail-stats > div {
  display: grid;
  gap: 3px;
  padding: 12px 8px;
  text-align: center;
}

.rail-stats > div + div {
  border-left: 1px solid var(--line);
}

.rail-stats strong {
  font-size: 14px;
  line-height: 1;
}

.rail-stats span {
  color: var(--muted);
  font-size: 9px;
}

.rail-section {
  padding: 13px 14px;
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
  font-size: 10px;
  font-weight: 800;
  letter-spacing: 0.08em;
}

.rail-label svg {
  color: var(--accent);
}

.rail-chips {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.rail-chips button,
.rail-density button {
  min-height: 27px;
  border: 1px solid var(--line);
  border-radius: 999px;
  background: #fff;
  color: var(--ink-2);
  padding: 0 10px;
  font-size: 11px;
  cursor: pointer;
}

.rail-chips button:hover,
.rail-density button:hover {
  border-color: rgba(255, 87, 34, 0.42);
  color: var(--accent);
}

.rail-chips button.active,
.rail-density button.active {
  border-color: var(--accent);
  background: var(--accent);
  color: #fff;
}

.density-section {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}

.density-section .rail-label {
  margin: 0;
}

.rail-density {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 6px;
}

.rail-custom-limit {
  width: 76px;
  height: 27px;
  box-sizing: border-box;
  border: 1px solid var(--line);
  border-radius: 999px;
  background: #fff;
  color: var(--ink);
  padding: 0 8px;
  font: inherit;
  font-size: 11px;
  text-align: center;
  outline: none;
}

.rail-custom-limit:focus,
.rail-custom-limit.active {
  border-color: var(--accent);
  box-shadow: 0 0 0 2px rgba(255, 87, 34, 0.1);
}

.rail-custom-limit::-webkit-inner-spin-button,
.rail-custom-limit::-webkit-outer-spin-button {
  margin: 0;
  appearance: none;
}

.rail-custom-limit {
  appearance: textfield;
}

@media (max-width: 720px) {
  .side-rail {
    top: 58px;
    left: 12px;
  }

  .rail-popover {
    max-height: calc(100vh - 118px);
  }
}
</style>
