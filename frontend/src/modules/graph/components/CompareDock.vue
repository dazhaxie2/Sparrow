<template>
  <section class="compare-dock" aria-label="技术对比">
    <div class="compare-head">
      <div>
        <GitCompare :size="15" />
        <strong>技术对比</strong>
        <span>{{ compareNodes.length }}/2</span>
      </div>
      <button type="button" title="清空对比" @click="$emit('clear')">
        <X :size="15" />
      </button>
    </div>

    <div class="compare-nodes">
      <article v-for="node in compareNodes" :key="node.id">
        <button class="remove-compare" type="button" title="移出对比" @click="$emit('remove', node.id)">
          <X :size="13" />
        </button>
        <span>{{ node.era }}</span>
        <strong>{{ node.name }}</strong>
        <small>{{ node.summary }}</small>
      </article>
    </div>

    <div v-if="compareNodes.length === 2" class="compare-result">
      <div>
        <span>共同前置</span>
        <p v-if="commonPrerequisites.length">{{ commonPrerequisites.slice(0, 12).map(node => node.name).join('、') }}<span v-if="commonPrerequisites.length > 12" class="more-count"> …等 {{ commonPrerequisites.length }} 项</span></p>
        <p v-else>暂无共同前置节点</p>
      </div>
      <div>
        <span>分叉方向</span>
        <p>{{ branchSummary }}</p>
      </div>
    </div>
    <p v-else class="compare-hint">再加入一个节点即可查看共同前置和分叉关系。</p>
  </section>
</template>

<script setup lang="ts">
import { GitCompare, X } from '@lucide/vue'
import type { NodeBrief } from '../types'

defineProps<{
  compareNodes: NodeBrief[]
  commonPrerequisites: NodeBrief[]
  branchSummary: string
}>()

defineEmits<{
  remove: [id: number]
  clear: []
}>()
</script>

<style scoped>
.compare-dock {
  position: fixed;
  left: 18px;
  bottom: 18px;
  z-index: 90;
  width: min(560px, calc(100vw - 36px));
  max-height: min(60vh, 560px);
  overflow-y: auto;
  border: 1px solid var(--ink);
  background: var(--panel);
  box-shadow: var(--shadow-md);
}

.compare-head {
  min-height: 40px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding: 0 12px;
  border-bottom: 1px solid var(--line);
  background: var(--surface);
}

.compare-head div,
.compare-head button {
  display: inline-flex;
  align-items: center;
  gap: 7px;
}

.compare-head svg {
  color: var(--accent);
}

.compare-head span {
  color: var(--muted);
  font-size: 12px;
}

.compare-head button {
  border: 0;
  background: transparent;
  color: var(--muted);
  cursor: pointer;
}

.compare-nodes {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
  padding: 12px;
}

.compare-nodes article {
  position: relative;
  display: grid;
  gap: 6px;
  min-height: 106px;
  border: 1px solid var(--line);
  background: var(--surface);
  padding: 12px;
}

.compare-nodes article span {
  color: var(--accent);
  font-size: 11px;
  font-weight: 800;
  letter-spacing: 0.08em;
}

.compare-nodes article strong {
  font-size: 16px;
}

.compare-nodes article small {
  display: -webkit-box;
  overflow: hidden;
  color: var(--ink-2);
  font-size: 12px;
  line-height: 1.6;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
}

.remove-compare {
  position: absolute;
  top: 8px;
  right: 8px;
  display: grid;
  place-items: center;
  width: 22px;
  height: 22px;
  border: 1px solid var(--line-strong);
  background: var(--panel);
  color: var(--muted);
  cursor: pointer;
}

.compare-result {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 10px;
  padding: 0 12px 12px;
}

.compare-result div,
.compare-hint {
  border-top: 1px solid var(--line);
  padding-top: 10px;
}

.compare-result span {
  color: var(--accent);
  font-size: 11px;
  font-weight: 800;
  letter-spacing: 0.08em;
}

.compare-result p,
.compare-hint {
  margin-top: 5px;
  color: var(--ink-2);
  font-size: 12px;
  line-height: 1.7;
}

.compare-result .more-count {
  color: var(--muted);
}

.compare-hint {
  margin: 0 12px 12px;
}

@media (max-width: 920px) {
  .compare-dock {
    left: 12px;
    right: 12px;
    bottom: 12px;
    width: auto;
  }

  .compare-result,
  .compare-nodes {
    grid-template-columns: 1fr;
  }
}
</style>
