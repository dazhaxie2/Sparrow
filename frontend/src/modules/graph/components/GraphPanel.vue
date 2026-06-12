<template>
  <aside class="panel">
    <div class="panel-header">
      <div>
        <span class="panel-symbol">◇</span>
        <strong>DETAIL PANEL</strong>
      </div>
      <span class="panel-state">{{ detail ? 'NODE SELECTED' : 'WAITING' }}</span>
    </div>

    <template v-if="!detail">
      <section class="empty-state">
        <p class="eyebrow">SPARROW GUIDE</p>
        <h2>选择一个技术节点</h2>
        <p>
          点击图谱中的任意节点，右侧会展示摘要、年代、直接前置技术和后续解锁路径。被选中的依赖链会用橙色高亮。
        </p>
        <div class="quick-list">
          <div><span>01</span>拖动画布浏览长链路</div>
          <div><span>02</span>滚轮缩放观察局部结构</div>
          <div><span>03</span>用 AI 向导追问关键概念</div>
        </div>
      </section>
    </template>

    <template v-else>
      <section class="node-section">
        <div class="node-meta">
          <span>{{ detail.era }}</span>
          <span>{{ detail.yearLabel }}</span>
        </div>
        <h2 class="node-title">
          {{ detail.name }}
          <span v-if="detail.premium" class="premium-badge">PRO DEPTH</span>
        </h2>
        <p class="node-summary">{{ detail.summary }}</p>
      </section>

      <section v-if="detail.locked" class="locked-box">
        <strong>会员深度内容</strong>
        <p>该节点的完整解读、案例和延伸阅读属于会员专属内容。</p>
        <button class="btn accent" type="button" @click="$emit('openMember')">开通会员解锁</button>
      </section>

      <section v-else-if="detail.detail" class="detail-copy">
        {{ detail.detail }}
      </section>

      <section v-if="detail.prerequisites.length" class="relation-block">
        <div class="rel-title">
          <span>←</span>
          直接前置 ({{ detail.prerequisites.length }})
        </div>
        <div class="rel-chips">
          <button
            v-for="item in detail.prerequisites"
            :key="item.id"
            type="button"
            @click="$emit('select', item.id)"
          >
            {{ item.name }}
          </button>
        </div>
      </section>

      <section v-if="detail.unlocks.length" class="relation-block">
        <div class="rel-title">
          <span>→</span>
          直接解锁 ({{ detail.unlocks.length }})
        </div>
        <div class="rel-chips">
          <button
            v-for="item in detail.unlocks"
            :key="item.id"
            type="button"
            @click="$emit('select', item.id)"
          >
            {{ item.name }}
          </button>
        </div>
      </section>
    </template>
  </aside>
</template>

<script setup lang="ts">
import type { NodeDetail } from '../types'

defineProps<{ detail: NodeDetail | null }>()
defineEmits<{ select: [id: number]; openMember: [] }>()
</script>

<style scoped>
.panel {
  width: 372px;
  display: flex;
  flex-direction: column;
  min-height: 0;
  border-left: 1px solid var(--line);
  background: var(--panel);
  overflow-y: auto;
}

.panel-header {
  position: sticky;
  top: 0;
  z-index: 1;
  min-height: 46px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding: 0 16px;
  border-bottom: 1px solid var(--line);
  background: var(--surface);
}

.panel-header div {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
  letter-spacing: 0.08em;
}

.panel-symbol {
  color: var(--accent);
}

.panel-state {
  border: 1px solid var(--line-strong);
  padding: 3px 7px;
  color: var(--muted);
  font-size: 10px;
  letter-spacing: 0.08em;
}

.empty-state,
.node-section,
.relation-block,
.detail-copy,
.locked-box {
  margin: 18px;
}

.eyebrow {
  color: var(--accent);
  font-size: 11px;
  font-weight: 800;
  letter-spacing: 0.12em;
}

.empty-state h2,
.node-title {
  margin-top: 8px;
  font-size: 24px;
  line-height: 1.2;
  letter-spacing: 0;
}

.empty-state p {
  margin-top: 12px;
  color: var(--ink-2);
  font-size: 14px;
  line-height: 1.85;
}

.quick-list {
  margin-top: 20px;
  border-top: 1px solid var(--line);
}

.quick-list div {
  display: flex;
  align-items: center;
  gap: 10px;
  min-height: 42px;
  border-bottom: 1px solid var(--line);
  color: var(--ink-2);
  font-size: 13px;
}

.quick-list span {
  color: var(--accent);
  font-weight: 800;
}

.node-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.node-meta span {
  border: 1px solid var(--line);
  background: var(--surface);
  padding: 5px 8px;
  color: var(--ink-2);
  font-size: 11px;
}

.node-title {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}

.premium-badge {
  border: 1px solid rgba(255, 87, 34, 0.35);
  color: var(--accent);
  padding: 4px 7px;
  font-size: 10px;
  letter-spacing: 0.08em;
}

.node-summary {
  margin-top: 12px;
  color: var(--ink);
  font-size: 14px;
  line-height: 1.9;
}

.detail-copy {
  border-top: 1px solid var(--line);
  border-bottom: 1px solid var(--line);
  padding: 14px 0;
  color: var(--ink-2);
  font-size: 13.5px;
  line-height: 1.9;
  white-space: pre-wrap;
}

.locked-box {
  border: 1px dashed rgba(255, 87, 34, 0.55);
  background: rgba(255, 87, 34, 0.06);
  padding: 14px;
}

.locked-box strong {
  color: var(--accent);
  font-size: 13px;
}

.locked-box p {
  margin: 8px 0 12px;
  color: var(--ink-2);
  font-size: 13px;
  line-height: 1.7;
}

.rel-title {
  display: flex;
  align-items: center;
  gap: 8px;
  padding-bottom: 9px;
  border-bottom: 1px solid var(--line);
  color: var(--ink-2);
  font-size: 12px;
  font-weight: 800;
  letter-spacing: 0.08em;
  text-transform: uppercase;
}

.rel-title span {
  color: var(--accent);
  font-size: 15px;
}

.rel-chips {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  padding-top: 12px;
}

.rel-chips button,
.btn {
  min-height: 32px;
  border: 1px solid var(--line-strong);
  background: var(--panel);
  padding: 0 10px;
  color: var(--ink);
  font-size: 12px;
  cursor: pointer;
  transition: border-color 0.16s ease, color 0.16s ease, background 0.16s ease;
}

.rel-chips button:hover {
  border-color: var(--accent);
  color: var(--accent);
  background: rgba(255, 87, 34, 0.05);
}

.btn.accent {
  border-color: var(--accent);
  background: var(--accent);
  color: var(--bg);
  font-weight: 800;
}

@media (max-width: 920px) {
  .panel {
    width: 100%;
    min-height: 420px;
    border-left: 0;
    border-top: 1px solid var(--line);
  }
}
</style>
