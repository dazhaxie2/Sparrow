<template>
  <div class="ai-composer">
    <section class="context-card" :class="{ muted: !contextNode }">
      <div>
        <span>当前上下文</span>
        <strong>{{ contextNode ? `正在围绕「${contextNode.name}」提问` : '选择节点后获得定向回答' }}</strong>
        <small v-if="contextNode">{{ contextNode.era }} · {{ contextNode.yearLabel }}</small>
      </div>
    </section>

    <div class="quick-prompts">
      <button
        v-for="prompt in quickPrompts"
        :key="prompt"
        type="button"
        :disabled="loading"
        @click="emit('submit', prompt)"
      >
        {{ prompt }}
      </button>
    </div>

    <div class="ai-input-row">
      <input
        v-model="input"
        type="text"
        maxlength="500"
        :placeholder="contextNode ? `追问「${contextNode.name}」...` : '问一个技术问题...'"
        @keydown.enter.prevent="doAsk"
      />
      <button class="btn" type="button" :disabled="loading || !input.trim()" @click="doAsk">
        <Send :size="15" />
      </button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { Send } from '@lucide/vue'
import type { NodeBrief } from '../../../shared/types/graph'

const props = defineProps<{
  contextNode: NodeBrief | null
  loading: boolean
}>()

const emit = defineEmits<{
  submit: [text: string]
}>()

const input = ref('')

const quickPrompts = computed(() => {
  if (!props.contextNode) return ['怎么开始学习？', '推荐一条路线', '哪些节点最关键？']
  return ['它依赖什么？', '为什么重要？', '推荐下一步学什么？']
})

function doAsk() {
  const question = input.value.trim()
  if (!question) return
  emit('submit', question)
  input.value = ''
}
</script>

<style scoped>
.ai-composer {
  display: flex;
  flex-direction: column;
}

.context-card {
  display: grid;
  gap: 4px;
  margin: 12px 12px 0;
  border: 1px solid rgba(255, 87, 34, 0.34);
  background: rgba(255, 87, 34, 0.06);
  padding: 10px 11px;
}

.context-card.muted {
  border-color: var(--line);
  background: var(--surface);
}

.context-card span {
  color: var(--accent);
  font-size: 11px;
  font-weight: 800;
  letter-spacing: 0.08em;
}

.context-card strong {
  font-size: 13px;
}

.context-card small {
  color: var(--muted);
  font-size: 12px;
}

.quick-prompts {
  display: flex;
  flex-wrap: wrap;
  gap: 7px;
  padding: 10px 12px 0;
}

.quick-prompts button {
  min-height: 28px;
  border: 1px solid var(--line-strong);
  background: var(--panel);
  color: var(--ink-2);
  padding: 0 9px;
  font-size: 12px;
  cursor: pointer;
  transition: border-color 0.16s ease, color 0.16s ease, background 0.16s ease;
}

.quick-prompts button:hover:not(:disabled) {
  border-color: var(--accent);
  background: rgba(255, 87, 34, 0.05);
  color: var(--accent);
}

.quick-prompts button:disabled {
  color: var(--muted);
  cursor: default;
}

.ai-input-row {
  display: flex;
  gap: 8px;
  padding: 12px;
  border-top: 1px solid var(--line);
  background: var(--surface);
  margin-top: 12px;
}

.ai-input-row input {
  flex: 1;
  min-width: 0;
  height: 36px;
  border: 1px solid var(--line-strong);
  background: var(--panel);
  color: var(--ink);
  padding: 0 10px;
  font-size: 13px;
  outline: none;
}

.ai-input-row input:focus {
  border-color: var(--ink);
}

.btn {
  width: 42px;
  display: grid;
  place-items: center;
  border: 1px solid var(--ink);
  background: var(--ink);
  color: var(--bg);
  cursor: pointer;
}

.btn:disabled {
  border-color: var(--line-strong);
  background: #d8d8d8;
  color: var(--muted);
  cursor: default;
}
</style>
