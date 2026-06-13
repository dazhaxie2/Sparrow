<template>
  <div class="ai-dock" :class="{ collapsed }">
    <button class="ai-toggle" type="button" @click="collapsed = !collapsed">
      <span class="toggle-mark"><Bot :size="16" /></span>
      <span class="toggle-copy">AI 向导</span>
      <span class="toggle-state">{{ collapsed ? '打开' : '收起' }}</span>
    </button>

    <div class="ai-body">
      <div class="ai-head">
        <span><Sparkles :size="14" /> 上下文助手</span>
        <button type="button" title="清空对话" @click="clearMessages">
          <Trash2 :size="14" />
        </button>
      </div>

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
          @click="askWithText(prompt)"
        >
          {{ prompt }}
        </button>
      </div>

      <div ref="messagesRef" class="ai-messages">
        <div v-for="(msg, i) in messages" :key="i" class="msg" :class="msg.role === 'user' ? 'user' : 'bot'">
          {{ msg.content }}
          <span v-if="msg.sources?.length" class="src">
            来源: {{ msg.sources.map(source => source.name).join('、') }}
          </span>
        </div>

        <div v-if="loading" class="msg bot typing">
          <LoaderCircle class="spin" :size="15" />
          {{ phase || '正在处理' }}
        </div>
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
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, ref } from 'vue'
import { Bot, LoaderCircle, Send, Sparkles, Trash2 } from '@lucide/vue'
import { useChat } from '../composables/useChat'
import { useUserStore } from '../../user/store'
import type { NodeBrief } from '../../graph/types'

const props = defineProps<{ contextNode: NodeBrief | null }>()

const user = useUserStore()
const { messages, loading, phase, ask, clearMessages } = useChat()
const collapsed = ref(true)
const input = ref('')
const messagesRef = ref<HTMLElement | null>(null)

const quickPrompts = computed(() => {
  if (!props.contextNode) return ['怎么开始学习？', '推荐一条路线', '哪些节点最关键？']
  return ['它依赖什么？', '为什么重要？', '推荐下一步学什么？']
})

async function doAsk() {
  await askWithText(input.value)
  input.value = ''
}

async function askWithText(text: string) {
  const question = text.trim()
  if (!question) return
  const contextualQuestion = props.contextNode
    ? `围绕「${props.contextNode.name}」回答：${question}`
    : question
  collapsed.value = false
  await ask(contextualQuestion, user.isLoggedIn())
  await nextTick()
  if (messagesRef.value) messagesRef.value.scrollTop = messagesRef.value.scrollHeight
}

function open() {
  collapsed.value = false
}

function close() {
  collapsed.value = true
}

defineExpose({ open, close })
</script>

<style scoped>
.ai-dock {
  position: fixed;
  right: 392px;
  bottom: 18px;
  width: 390px;
  background: var(--panel);
  border: 1px solid var(--ink);
  box-shadow: var(--shadow-md);
  z-index: 50;
}

.ai-toggle {
  width: 100%;
  min-height: 42px;
  display: grid;
  grid-template-columns: 34px 1fr auto;
  align-items: center;
  gap: 9px;
  border: 0;
  border-bottom: 1px solid var(--line);
  background: var(--panel);
  padding: 0 12px;
  text-align: left;
  cursor: pointer;
}

.toggle-mark {
  display: grid;
  place-items: center;
  width: 26px;
  height: 26px;
  background: var(--ink);
  color: var(--bg);
}

.toggle-copy {
  font-weight: 800;
  letter-spacing: 0.04em;
}

.toggle-state {
  color: var(--accent);
  font-size: 12px;
  font-weight: 800;
}

.ai-dock.collapsed {
  width: 214px;
}

.ai-dock.collapsed .ai-toggle {
  border-bottom: 0;
}

.ai-dock.collapsed .ai-body {
  display: none;
}

.ai-body {
  background: var(--panel);
}

.ai-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  min-height: 38px;
  padding: 0 12px;
  border-bottom: 1px solid var(--line);
  color: var(--ink);
  font-size: 11px;
  font-weight: 800;
  letter-spacing: 0.08em;
}

.ai-head span,
.ai-head button {
  display: inline-flex;
  align-items: center;
  gap: 7px;
}

.ai-head svg {
  color: var(--accent);
}

.ai-head button {
  border: 0;
  background: transparent;
  color: var(--muted);
  cursor: pointer;
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

.ai-messages {
  height: 252px;
  overflow-y: auto;
  padding: 12px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.msg {
  border: 1px solid var(--line);
  padding: 10px 11px;
  font-size: 13px;
  line-height: 1.7;
  white-space: pre-wrap;
  word-break: break-word;
}

.msg.bot {
  align-self: flex-start;
  max-width: 92%;
  background: var(--surface);
  color: var(--ink);
}

.msg.user {
  align-self: flex-end;
  max-width: 86%;
  border-color: var(--accent);
  background: var(--accent);
  color: var(--bg);
}

.msg.typing {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  color: var(--ink-2);
}

.msg .src {
  display: block;
  margin-top: 7px;
  color: var(--muted);
  font-size: 12px;
}

.msg.user .src {
  color: rgba(255, 255, 255, 0.75);
}

.ai-input-row {
  display: flex;
  gap: 8px;
  padding: 12px;
  border-top: 1px solid var(--line);
  background: var(--surface);
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

.spin {
  animation: spin 0.9s linear infinite;
}

@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}

@media (max-width: 920px) {
  .ai-dock {
    right: 16px;
    width: calc(100vw - 32px);
  }

  .ai-dock.collapsed {
    width: 214px;
  }
}
</style>
