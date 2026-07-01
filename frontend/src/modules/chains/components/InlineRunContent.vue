<template><component :is="render" /></template>

<script setup lang="ts">
import { computed, h } from 'vue'
import type { InlineRun } from '../researchTypes'

const props = defineProps<{ run: InlineRun }>()

// 按标记顺序逐层包裹(对照 BettaFish _render_inline 的前后缀包裹逻辑)。
const render = computed(() => {
  let node: any = h('span', escapeHtml(props.run.text ?? ''))
  for (const mark of props.run.marks ?? []) {
    switch (mark.type) {
      case 'bold': node = h('strong', node); break
      case 'italic': node = h('em', node); break
      case 'code': node = h('code', { class: 'il-code' }, node); break
      case 'highlight': node = h('mark', node); break
      case 'color': node = h('span', { style: { color: mark.value ?? undefined } }, node); break
      // source / link 在 InlineRuns.vue 顶层处理，这里不重复
    }
  }
  return node
})

function escapeHtml(v: string) {
  return v.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
}
</script>

<style scoped>
:deep(.il-code) { padding: 2px 6px; border-radius: 4px; background: var(--surface); font-size: .92em; }
</style>
