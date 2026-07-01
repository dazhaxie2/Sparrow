<template>
  <span class="inline-runs">
    <template v-for="(run, i) in inlines" :key="i">
      <!-- source 引用：可点击徽章 -->
      <button
        v-if="hasMark(run, 'source')"
        class="src-badge"
        :title="sourceOf(run)?.title"
        @click="emit('source-click', markValue(run, 'source') ?? '')"
      >{{ markValue(run, 'source') }}</button>

      <component :is="wrapLink(run)" v-else-if="hasMark(run, 'link')" :href="markHref(run) ?? '#'" target="_blank" rel="noreferrer">
        <InlineRunContent :run="run" />
      </component>

      <InlineRunContent v-else :run="run" />
    </template>
  </span>
</template>

<script setup lang="ts">
import InlineRunContent from './InlineRunContent.vue'
import type { InlineMark, InlineRun, ResearchSource } from '../model/types'

const props = defineProps<{ inlines: InlineRun[]; sources: ResearchSource[] }>()
const emit = defineEmits<{ (e: 'source-click', ref: string): void }>()

const sourceMap = () => new Map(props.sources.map(s => [s.sourceRef, s]))
function hasMark(run: InlineRun, type: string) { return (run.marks ?? []).some(m => m.type === type) }
function markValue(run: InlineRun, type: string) { return (run.marks ?? []).find(m => m.type === type)?.value ?? null }
function markHref(run: InlineRun) { return (run.marks ?? []).find(m => m.type === 'link')?.href ?? null }
function sourceOf(run: InlineRun) { const ref = markValue(run, 'source'); return ref ? sourceMap().get(ref) : undefined }
function wrapLink(run: InlineRun): InlineMark | string { return hasMark(run, 'link') ? 'a' : 'span' }
</script>

<style scoped>
.src-badge { display: inline-flex; align-items: center; margin: 0 2px; padding: 1px 6px; border: 1px solid rgba(255,87,34,.35); border-radius: 99px; background: rgba(255,87,34,.08); color: var(--accent); font-size: 10px; font-weight: 700; cursor: pointer; vertical-align: baseline; }
.src-badge:hover { background: rgba(255,87,34,.18); }
</style>
