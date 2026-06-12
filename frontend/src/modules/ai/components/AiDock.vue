<template>
  <div class="ai-dock" :class="{ collapsed }">
    <button class="ai-toggle" type="button" @click="collapsed = !collapsed">
      <span class="toggle-mark">AI</span>
      <span class="toggle-copy">智能向导</span>
      <span class="toggle-state">{{ collapsed ? '打开' : '收起' }}</span>
    </button>

    <div class="ai-body">
      <div class="ai-head">
        <span>◆ ASSISTANT CONSOLE</span>
        <span>{{ messages.length }} LOGS</span>
      </div>

      <div ref="messagesRef" class="ai-messages">
        <div v-if="!messages.length" class="empty-message">
          询问任意技术节点、前置知识或学习路径，AI 会结合科技树上下文回答。
        </div>
        <div v-for="(msg, i) in messages" :key="i" class="msg" :class="msg.role === 'user' ? 'user' : 'bot'">
          {{ msg.content }}
          <span v-if="msg.sources?.length" class="src">
            来源: {{ msg.sources.map(source => source.name).join('、') }}
          </span>
        </div>
      </div>

      <div class="ai-input-row">
        <input
          v-model="input"
          type="text"
          maxlength="500"
          placeholder="问一个技术问题..."
          @keydown.enter.prevent="doAsk"
        />
        <button class="btn" type="button" :disabled="loading || !input.trim()" @click="doAsk">
          {{ loading ? '...' : '发送' }}
        </button>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { nextTick, ref } from 'vue'
import { useChat } from '../composables/useChat'
import { useUserStore } from '../../user/store'

const user = useUserStore()
const { messages, loading, ask } = useChat()
const collapsed = ref(true)
const input = ref('')
const messagesRef = ref<HTMLElement | null>(null)

async function doAsk() {
  const question = input.value.trim()
  if (!question) return
  input.value = ''
  await ask(question, user.isLoggedIn())
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
  font-size: 11px;
  font-weight: 800;
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
  color: var(--muted);
  font-size: 11px;
  font-weight: 800;
  letter-spacing: 0.08em;
}

.ai-head span:first-child {
  color: var(--ink);
}

.ai-messages {
  height: 278px;
  overflow-y: auto;
  padding: 12px;
  display: flex;
  flex-direction: column;
  gap: 10px;
  background: linear-gradient(#ffffff, #ffffff), var(--surface);
}

.empty-message,
.msg {
  border: 1px solid var(--line);
  padding: 10px 11px;
  font-size: 13px;
  line-height: 1.7;
  white-space: pre-wrap;
  word-break: break-word;
}

.empty-message {
  color: var(--ink-2);
  background: var(--surface);
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
  min-width: 64px;
  border: 1px solid var(--ink);
  background: var(--ink);
  color: var(--bg);
  padding: 0 12px;
  font-size: 13px;
  font-weight: 800;
  cursor: pointer;
}

.btn:disabled {
  border-color: var(--line-strong);
  background: #d8d8d8;
  color: var(--muted);
  cursor: default;
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
