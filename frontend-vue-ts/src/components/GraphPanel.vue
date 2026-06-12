<template>
  <aside class="panel">
    <template v-if="!detail">
      <div class="panel-placeholder">
        <h3>👋 欢迎来到人类科技树</h3>
        <p>从 50 万年前的「火」到未来的 AGI,共 77 项关键技术、110+ 条依赖关系。</p>
        <p>
          · 点击任意节点查看详情与前置技术链<br />
          · 拖拽平移、滚轮缩放<br />
          · 右下角召唤 AI 向导提问
        </p>
      </div>
    </template>
    <template v-else>
      <div class="node-era">{{ detail.era }} · {{ detail.yearLabel }}</div>
      <div class="node-title">
        {{ detail.name }}
        <span v-if="detail.premium" class="crown">👑会员深度</span>
      </div>
      <div class="node-summary">{{ detail.summary }}</div>
      <div v-if="detail.locked" class="locked-box">
        🔒 本节点的深度解读为会员专属内容<br />
        <button class="btn gold" @click="$emit('openMember')">👑 开通会员解锁</button>
      </div>
      <div v-else-if="detail.detail" class="node-detail">{{ detail.detail }}</div>

      <div v-if="detail.prerequisites.length" class="rel-title">
        ⬅ 直接前置({{ detail.prerequisites.length }})
      </div>
      <div v-if="detail.prerequisites.length" class="rel-chips">
        <span v-for="p in detail.prerequisites" :key="p.id" @click="$emit('select', p.id)">
          {{ p.name }}
        </span>
      </div>

      <div v-if="detail.unlocks.length" class="rel-title">
        ➡ 直接解锁({{ detail.unlocks.length }})
      </div>
      <div v-if="detail.unlocks.length" class="rel-chips">
        <span v-for="u in detail.unlocks" :key="u.id" @click="$emit('select', u.id)">
          {{ u.name }}
        </span>
      </div>
    </template>
  </aside>
</template>

<script setup lang="ts">
import type { NodeDetail } from '../api/graph'

defineProps<{ detail: NodeDetail | null }>()
defineEmits<{ select: [id: number]; openMember: [] }>()
</script>

<style scoped>
.panel {
  width: 340px;
  border-left: 1px solid var(--line);
  background: var(--bg-2);
  padding: 20px;
  overflow-y: auto;
}
.panel h3 {
  margin-bottom: 10px;
}
.panel-placeholder p {
  color: var(--ink-2);
  font-size: 14px;
  line-height: 1.9;
  margin-top: 10px;
}
.node-era {
  font-size: 12.5px;
  color: var(--ink-2);
  margin-bottom: 6px;
}
.node-title {
  font-size: 20px;
  font-weight: 700;
  margin-bottom: 4px;
}
.node-title .crown {
  color: var(--gold);
  font-size: 15px;
}
.node-summary {
  font-size: 14px;
  line-height: 1.8;
  color: var(--ink);
  margin: 12px 0;
}
.node-detail {
  font-size: 13.5px;
  line-height: 1.8;
  color: var(--ink-2);
  margin-bottom: 14px;
}
.locked-box {
  background: rgba(246, 194, 68, 0.08);
  border: 1px dashed var(--gold);
  border-radius: 10px;
  padding: 14px;
  font-size: 13px;
  color: var(--gold);
  margin-bottom: 14px;
}
.locked-box .btn {
  margin-top: 10px;
}
.rel-title {
  font-size: 12.5px;
  color: var(--ink-2);
  margin: 14px 0 6px;
  letter-spacing: 0.05em;
}
.rel-chips {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
.rel-chips span {
  background: #1d2a44;
  border: 1px solid var(--line);
  border-radius: 6px;
  padding: 3px 9px;
  font-size: 12.5px;
  cursor: pointer;
}
.rel-chips span:hover {
  border-color: var(--accent);
  color: var(--accent);
}
.btn.gold {
  background: rgba(246, 194, 68, 0.12);
  border: 1px solid var(--gold);
  border-radius: 8px;
  color: var(--gold);
  padding: 7px 14px;
  font-size: 14px;
  cursor: pointer;
}
</style>
