<template>
  <div class="pest-item">
    <div class="pest-item-title">{{ item.title }}</div>
    <div v-if="item.detail" class="pest-item-desc">{{ item.detail }}</div>
    <div class="pest-item-meta">
      <span v-if="item.trend" :class="['pest-trend', `trend-${trendKey(item.trend)}`]">{{ item.trend }}</span>
      <span v-if="item.source" class="pest-source">来源：{{ item.source }}</span>
    </div>
  </div>
</template>

<script setup lang="ts">
import type { PestItem } from '../researchTypes'
defineProps<{ item: PestItem }>()
function trendKey(trend: string) {
  if (trend.includes('利')) return 'positive'
  if (trend.includes('负面') || trend.includes('不利')) return 'negative'
  if (trend.includes('中性')) return 'neutral'
  return 'uncertain'
}
</script>

<style scoped>
.pest-item { padding: 6px 0; border-top: 1px dashed var(--line); }
.pest-item:first-of-type { border-top: 0; }
.pest-item-title { font-weight: 700; font-size: 13px; }
.pest-item-desc { color: var(--ink-2); font-size: 12px; margin-top: 2px; }
.pest-item-meta { display: flex; gap: 8px; margin-top: 4px; font-size: 10px; }
.pest-trend { padding: 1px 6px; border-radius: 99px; font-weight: 700; background: rgba(0,0,0,.06); }
.pest-trend.trend-positive { background: #eaf7ee; color: #237a3b; }
.pest-trend.trend-negative { background: #fff0f0; color: var(--danger); }
.pest-source { color: var(--muted); }
</style>
