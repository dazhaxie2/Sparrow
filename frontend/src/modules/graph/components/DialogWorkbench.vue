<template>
  <section class="conversation-workbench" aria-label="关联节点对话工作台">
    <header class="workbench-header">
      <div>
        <BrainCircuit :size="17" />
        <strong>SPARROW AGENT</strong>
        <span>{{ dialogLoading ? 'BUILDING' : dialogActive ? 'GRAPH READY' : 'READY' }}</span>
      </div>
      <button type="button" title="返回图谱模式" @click="$emit('switchToMap')">
        <X :size="16" />
      </button>
    </header>

    <div ref="feedRef" class="dialog-feed">
      <div v-if="!dialogMessages.length" class="dialog-empty">
        <MessageSquareText :size="24" />
        <strong>与 Agent 对话</strong>
        <span>例如：蒸汽机如何影响铁路和电力？</span>
      </div>

      <article
        v-for="message in dialogMessages"
        :key="message.id"
        class="dialog-message"
        :class="message.role"
      >
        <div class="message-meta">
          <strong>{{ message.role === 'user' ? 'YOU' : 'AGENT' }}</strong>
          <span v-if="message.title">{{ message.title }}</span>
        </div>
        <div v-if="message.role === 'assistant'" class="dialog-md" v-html="renderMarkdown(message.content)" />
        <p v-else>{{ message.content }}</p>
      </article>

      <div v-if="dialogLoading" class="dialog-state">
        <LoaderCircle class="spin" :size="16" />
        <span>BUILDING GRAPH MEMORY</span>
      </div>
      <div v-if="dialogError" class="dialog-error">{{ dialogError }}</div>
    </div>

    <form class="workbench-input" @submit.prevent="submit">
      <textarea
        ref="inputRef"
        v-model="text"
        rows="3"
        placeholder="Ask Agent..."
        :disabled="dialogLoading"
        @keydown.enter.exact.prevent="submit"
      ></textarea>
      <button type="submit" :disabled="dialogLoading || !text.trim()" title="发送">
        <LoaderCircle v-if="dialogLoading" class="spin" :size="16" />
        <SendHorizontal v-else :size="16" />
      </button>
    </form>
  </section>
</template>

<script setup lang="ts">
import { nextTick, onMounted, ref, watch } from 'vue'
import { BrainCircuit, LoaderCircle, MessageSquareText, SendHorizontal, X } from '@lucide/vue'
import { renderMarkdown } from '../../ai/utils/markdown'
import type { DialogMessage } from '../composables/useDialogMode'

const props = defineProps<{
  dialogMessages: DialogMessage[]
  dialogLoading: boolean
  dialogError: string
  dialogActive: boolean
}>()

const emit = defineEmits<{
  submit: [text: string]
  switchToMap: []
}>()

const text = ref('')
const inputRef = ref<HTMLTextAreaElement | null>(null)
const feedRef = ref<HTMLElement | null>(null)

function submit() {
  const value = text.value.trim()
  if (!value || props.dialogLoading) return
  emit('submit', value)
  text.value = ''
}

async function scrollToBottom() {
  await nextTick()
  if (feedRef.value) feedRef.value.scrollTop = feedRef.value.scrollHeight
}

watch(() => props.dialogMessages, scrollToBottom, { deep: true })
watch(() => props.dialogLoading, scrollToBottom)

onMounted(() => {
  inputRef.value?.focus()
})
</script>

<style scoped>
.conversation-workbench {
  flex: 0 0 min(440px, 34vw);
  min-width: 360px;
  display: flex;
  flex-direction: column;
  min-height: 0;
  border: 1px solid var(--line);
  border-radius: 8px;
  background: #ffffff;
  box-shadow: var(--shadow-sm);
  overflow: hidden;
}

.workbench-header {
  flex: none;
  min-height: 56px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 0 14px;
  border-bottom: 1px solid var(--line);
}

.workbench-header div {
  min-width: 0;
  display: inline-flex;
  align-items: center;
  gap: 8px;
}

.workbench-header svg {
  flex: none;
  color: var(--accent);
}

.workbench-header strong {
  font-size: 14px;
  letter-spacing: 0.04em;
}

.workbench-header span {
  color: var(--muted);
  font-size: 11px;
  font-weight: 900;
  letter-spacing: 0.08em;
}

.workbench-header button {
  display: grid;
  place-items: center;
  width: 32px;
  height: 32px;
  border: 1px solid var(--line);
  border-radius: 6px;
  background: var(--surface);
  color: var(--ink-2);
  cursor: pointer;
}

.workbench-header button:hover {
  border-color: var(--accent);
  color: var(--accent);
}

.dialog-feed {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  display: grid;
  align-content: start;
  gap: 12px;
  padding: 14px;
  background:
    linear-gradient(#ffffff, rgba(255, 255, 255, 0.94)),
    radial-gradient(#dddddd 1px, transparent 1px);
  background-size: auto, 20px 20px;
}

.dialog-empty {
  min-height: 240px;
  display: grid;
  place-content: center;
  justify-items: center;
  gap: 9px;
  color: var(--muted);
  text-align: center;
}

.dialog-empty svg {
  color: var(--accent);
}

.dialog-empty strong {
  color: var(--ink);
  font-size: 18px;
}

.dialog-empty span {
  font-size: 12px;
}

.dialog-message {
  display: grid;
  gap: 9px;
  border: 1px solid var(--line);
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.96);
  padding: 12px;
  box-shadow: var(--shadow-sm);
}

.dialog-message.user {
  border-color: rgba(17, 24, 39, 0.18);
  background: #f6f7f8;
}

.dialog-message.assistant {
  border-color: rgba(255, 87, 34, 0.2);
}

.message-meta {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.message-meta strong {
  font-size: 11px;
  letter-spacing: 0.08em;
}

.message-meta span {
  color: var(--accent);
  font-size: 10px;
  font-weight: 900;
  letter-spacing: 0.08em;
}

.dialog-message p {
  margin: 0;
  color: var(--ink-2);
  font-size: 13px;
  line-height: 1.7;
  white-space: pre-wrap;
}

.dialog-md {
  font-size: 13px;
  line-height: 1.7;
  color: var(--ink-2);
}

.dialog-md :deep(h3),
.dialog-md :deep(h4) {
  margin: 10px 0 5px;
  font-size: 13px;
  font-weight: 900;
  color: var(--ink);
}

.dialog-md :deep(h3:first-child) {
  margin-top: 0;
}

.dialog-md :deep(p) {
  margin: 0 0 7px;
}

.dialog-md :deep(ul),
.dialog-md :deep(ol) {
  margin: 0 0 7px;
  padding-left: 17px;
}

.dialog-md :deep(li) {
  margin: 2px 0;
}

.dialog-md :deep(a) {
  color: var(--accent);
}

.dialog-state,
.dialog-error {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  min-height: 32px;
  color: var(--ink-2);
  font-size: 12px;
}

.dialog-error {
  color: var(--danger);
}

.workbench-input {
  flex: none;
  display: grid;
  grid-template-columns: 1fr auto;
  gap: 10px;
  align-items: end;
  padding: 12px;
  border-top: 1px solid var(--line);
  background: #f7f8f9;
}

.workbench-input textarea {
  min-width: 0;
  min-height: 74px;
  max-height: 144px;
  resize: vertical;
  border: 1px solid var(--line);
  border-radius: 8px;
  outline: none;
  background: #ffffff;
  color: var(--ink);
  padding: 10px 12px;
  font: inherit;
  font-size: 13px;
  line-height: 1.6;
}

.workbench-input textarea:focus {
  border-color: var(--accent);
  box-shadow: 0 0 0 3px rgba(255, 87, 34, 0.1);
}

.workbench-input button {
  display: grid;
  place-items: center;
  width: 42px;
  height: 42px;
  border: 0;
  border-radius: 8px;
  background: var(--accent);
  color: #ffffff;
  cursor: pointer;
}

.workbench-input button:disabled {
  background: #d4d8dd;
  cursor: default;
}

.spin {
  animation: spin 0.9s linear infinite;
}

@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}

@media (max-width: 920px) {
  .conversation-workbench {
    flex: 0 0 auto;
    width: 100%;
    min-width: 0;
    min-height: 520px;
  }
}
</style>
