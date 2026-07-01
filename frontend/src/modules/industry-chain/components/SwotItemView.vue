<template>
  <div class="swot-item">
    <div class="swot-item-title">{{ item.title }}</div>
    <div v-if="item.detail" class="swot-item-desc">{{ item.detail }}</div>
    <span v-if="item.impact" :class="['swot-tag', `impact-${impactKey(item.impact)}`]">{{ item.impact }}</span>
  </div>
</template>

<script setup lang="ts">
import type { SwotItem } from '../model/types'
defineProps<{ item: SwotItem }>()
function impactKey(impact: string) { return impact.replace(/[高中低极]/g, '').trim() || 'mid' }
</script>

<style scoped>
.swot-item { padding: 6px 0; border-top: 1px dashed var(--line); }
.swot-item:first-of-type { border-top: 0; }
.swot-item-title { font-weight: 700; font-size: 13px; }
.swot-item-desc { color: var(--ink-2); font-size: 12px; margin-top: 2px; }
.swot-tag { display: inline-block; margin-top: 4px; padding: 1px 7px; border-radius: 99px; font-size: 10px; font-weight: 700; background: rgba(0,0,0,.06); }
</style>
