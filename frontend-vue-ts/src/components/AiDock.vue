<template>
  <div class="ai-dock" :class="{ collapsed }">
    <div class="ai-toggle" @click="collapsed = !collapsed">🤖 AI 向导</div>
    <div class="ai-body">
      <div ref="messagesRef" class="ai-messages">
        <div v-for="(msg, i) in messages" :key="i" class="msg" :class="msg.role">
          {{ msg.text }}
          <span v-if="msg.sources?.length" class="src">
            📎 来源: {{ msg.sources.map(s => s.name).join('、') }}
          </span>
        </div>
      </div>
      <div class="ai-input-row">
        <input
          v-model="input"
          type="text"
          maxlength="500"
          placeholder="问点什么…"
          @keydown.enter="ask"
        />
        <button class="btn" @click="ask">发送</button>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, nextTick } from 'vue'
import { askAi } from '../api/ai'
import type { SourceRef } from '../api/ai'
import { useUserStore } from '../stores/user'

interface ChatMsg {
  role: 'bot' | 'user'
  text: string
  sources?: SourceRef[]
}

const user = useUserStore()
const collapsed = ref(true)
const input = ref('')
const messages = ref<ChatMsg[]>([
  { role: 'bot', text: '你好!我是科技树 AI 向导。试试问我:「蒸汽机的前置技术有哪些?」' },
])
const messagesRef = ref<HTMLElement | null>(null)

async function ask() {
  const q = input.value.trim()
  if (!q) return
  if (!user.isLoggedIn()) {
    messages.value.push({ role: 'bot', text: '请先登录后再提问' })
    return
  }
  input.value = ''
  messages.value.push({ role: 'user', text: q })
  messages.value.push({ role: 'bot', text: '思考中…' })
  await nextTick()
  scrollBottom()

  try {
    const res = await askAi(q)
    messages.value.pop()
    messages.value.push({ role: 'bot', text: res.answer, sources: res.sources })
    if (res.remainingQuota >= 0) {
      messages.value.push({ role: 'bot', text: `今日剩余免费次数: ${res.remainingQuota}(会员不限次)` })
    }
  } catch (e: any) {
    messages.value.pop()
    if (e.code === 429) {
      messages.value.push({ role: 'bot', text: e.message })
    } else {
      messages.value.push({ role: 'bot', text: `出错了: ${e.message}` })
    }
  }
  await nextTick()
  scrollBottom()
}

function scrollBottom() {
  if (messagesRef.value) {
    messagesRef.value.scrollTop = messagesRef.value.scrollHeight
  }
}
</script>

<style scoped>
.ai-dock {
  position: fixed;
  right: 360px;
  bottom: 16px;
  width: 380px;
  background: var(--bg-2);
  border: 1px solid var(--line);
  border-radius: 14px;
  overflow: hidden;
  box-shadow: 0 8px 30px rgba(0, 0, 0, 0.45);
  z-index: 50;
}
.ai-toggle {
  padding: 12px 16px;
  cursor: pointer;
  font-weight: 600;
  user-select: none;
}
.ai-dock.collapsed .ai-body {
  display: none;
}
.ai-dock.collapsed {
  width: auto;
}
.ai-messages {
  height: 280px;
  overflow-y: auto;
  padding: 0 14px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.msg {
  padding: 9px 12px;
  border-radius: 10px;
  font-size: 13.5px;
  line-height: 1.7;
  white-space: pre-wrap;
  word-break: break-word;
}
.msg.bot {
  background: #1d2a44;
  align-self: flex-start;
  max-width: 92%;
}
.msg.user {
  background: var(--accent);
  color: #07101f;
  align-self: flex-end;
  max-width: 85%;
}
.msg .src {
  display: block;
  margin-top: 6px;
  font-size: 12px;
  color: var(--ink-2);
}
.ai-input-row {
  display: flex;
  gap: 8px;
  padding: 12px 14px;
  border-top: 1px solid var(--line);
}
.ai-input-row input {
  flex: 1;
  background: var(--bg);
  border: 1px solid var(--line);
  border-radius: 8px;
  color: var(--ink);
  padding: 8px 12px;
  font-size: 14px;
  outline: none;
}
.ai-input-row input:focus {
  border-color: var(--accent);
}
.btn {
  background: #1d2a44;
  color: var(--ink);
  border: 1px solid var(--line);
  border-radius: 8px;
  padding: 7px 14px;
  font-size: 14px;
  cursor: pointer;
}
.btn:hover {
  border-color: var(--accent);
}
</style>
