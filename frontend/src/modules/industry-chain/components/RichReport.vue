<template>
  <div class="rich-report">
    <nav v-if="toc.length" class="report-toc">
      <div class="toc-title">📚 目录</div>
      <a v-for="entry in toc" :key="entry.anchor" :href="`#${entry.anchor}`" class="toc-link" @click.prevent="scrollTo(entry.anchor)">
        {{ entry.display }}
      </a>
    </nav>

    <article class="report-body">
      <section v-for="chapter in chapters" :key="chapter.chapterId" :id="chapter.anchor" class="report-chapter">
        <h2 v-if="chapter.title" class="chapter-title">{{ chapter.title }}</h2>
        <p v-if="chapter.summary" class="chapter-summary">{{ chapter.summary }}</p>
        <template v-for="(block, i) in chapter.blocks" :key="i">
          <BlockView :block="block" :sources="sources" @source-click="emit('source-click', $event)" />
        </template>
      </section>
    </article>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import BlockView from './BlockView.vue'
import type { DocumentIr, ResearchSource } from '../model/types'

const props = defineProps<{ report: DocumentIr; sources: ResearchSource[] }>()
const emit = defineEmits<{ (e: 'source-click', ref: string): void }>()

const chapters = computed(() => props.report?.chapters ?? [])
const toc = computed(() => {
  const explicit = props.report?.metadata?.toc
  if (explicit && explicit.length) return explicit
  return chapters.value.map(chapter => ({ level: 1, display: chapter.title, anchor: chapter.anchor }))
})

function scrollTo(anchor: string) {
  const el = document.getElementById(anchor)
  if (el) el.scrollIntoView({ behavior: 'smooth', block: 'start' })
}
</script>

<style scoped>
.rich-report { display: grid; grid-template-columns: 220px 1fr; gap: 24px; }
.report-toc { position: sticky; top: 0; align-self: start; max-height: calc(100vh - 80px); overflow-y: auto; padding: 14px; border: 1px solid var(--line); border-radius: 8px; background: var(--panel); }
.toc-title { font-size: 12px; font-weight: 800; color: var(--accent); margin-bottom: 8px; letter-spacing: .05em; }
.toc-link { display: block; padding: 5px 8px; border-radius: 5px; color: var(--ink-2); font-size: 12px; text-decoration: none; }
.toc-link:hover { background: var(--surface); color: var(--accent); }
.report-body { min-width: 0; }
.report-chapter { margin-bottom: 36px; scroll-margin-top: 16px; }
.chapter-title { font-size: 20px; padding-bottom: 8px; margin-bottom: 14px; border-bottom: 2px solid var(--accent); }
.chapter-summary { color: var(--muted); font-size: 13px; margin-bottom: 14px; }
@media (max-width: 920px) { .rich-report { grid-template-columns: 1fr; } .report-toc { position: static; max-height: none; } }
</style>
