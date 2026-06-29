<template>
  <!-- 收起态:右边缘竖条把手(全屏沉浸时隐藏入口) -->
  <button
    v-if="!open && !immersive"
    class="ai-handle"
    type="button"
    title="打开 AI 向导"
    @click="open = true"
  >
    <Bot :size="16" />
    <span class="handle-text">AI 向导</span>
  </button>

  <!-- 展开态:遮罩 + 右侧抽屉浮层 -->
  <div v-if="open" class="ai-mask" @click="open = false" />
  <aside class="ai-drawer" :class="{ open }" aria-label="AI 向导">
    <div class="ai-head">
      <span><Sparkles :size="14" /> 上下文助手</span>
      <div class="head-actions">
        <button type="button" title="清空对话" @click="clearMessages">
          <Trash2 :size="14" />
        </button>
        <button type="button" title="收起" @click="open = false">
          <X :size="15" />
        </button>
      </div>
    </div>

    <AiMessageList :messages="messages" :loading="loading" :phase="phase" />

    <AiComposer :context-node="contextNode" :loading="loading" @submit="handleSubmit" />
  </aside>
</template>

<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { Bot, Sparkles, Trash2, X } from '@lucide/vue'
import { useChat } from '../composables/useChat'
import { useUserStore } from '../../user/store'
import type { NodeBrief } from '../../graph/types'
import AiMessageList from './AiMessageList.vue'
import AiComposer from './AiComposer.vue'

const props = defineProps<{
  contextNode: NodeBrief | null
  /** 全屏沉浸模式:隐藏右边缘的展开把手。 */
  immersive?: boolean
}>()

const user = useUserStore()
const { messages, loading, phase, ask, clearMessages } = useChat()

const OPEN_KEY = 'sparrow_ai_sidebar_open'
const open = ref(false)

/** 提交一个问题:带上当前节点上下文,交给真 AI,并确保抽屉展开。 */
async function handleSubmit(text: string) {
  const question = text.trim()
  if (!question) return
  const contextualQuestion = props.contextNode
    ? `围绕「${props.contextNode.name}」回答：${question}`
    : question
  open.value = true
  await ask(contextualQuestion, user.isLoggedIn())
}

// 记住用户上次是开还是关。
watch(open, value => {
  localStorage.setItem(OPEN_KEY, value ? '1' : '0')
})

onMounted(() => {
  open.value = localStorage.getItem(OPEN_KEY) === '1'
})

// 兼容旧用法:允许父组件通过 ref 控制开合。
defineExpose({
  open: () => {
    open.value = true
  },
  close: () => {
    open.value = false
  },
})
</script>

<style scoped>
/* 右边缘竖条把手 */
.ai-handle {
  position: fixed;
  top: 50%;
  right: 0;
  transform: translateY(-50%);
  z-index: 60;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  width: 36px;
  padding: 14px 0;
  border: 1px solid var(--ink);
  border-right: 0;
  border-radius: 10px 0 0 10px;
  background: var(--ink);
  color: var(--bg);
  cursor: pointer;
  box-shadow: -4px 0 16px rgba(0, 0, 0, 0.16);
  transition: padding 0.16s ease, background 0.16s ease;
}

.ai-handle:hover {
  padding: 18px 0;
  background: #000;
}

.handle-text {
  writing-mode: vertical-rl;
  letter-spacing: 0.12em;
  font-size: 12px;
  font-weight: 800;
}

/* 遮罩 */
.ai-mask {
  position: fixed;
  inset: 0;
  z-index: 90;
  background: rgba(20, 24, 29, 0.28);
  backdrop-filter: blur(1px);
}

/* 抽屉浮层 */
.ai-drawer {
  position: fixed;
  top: 0;
  right: 0;
  z-index: 100;
  width: min(390px, 100vw);
  height: 100vh;
  display: flex;
  flex-direction: column;
  background: var(--panel);
  border-left: 1px solid var(--ink);
  box-shadow: -18px 0 50px rgba(20, 24, 29, 0.18);
  transform: translateX(100%);
  transition: transform 0.24s cubic-bezier(0.22, 1, 0.36, 1);
}

.ai-drawer.open {
  transform: translateX(0);
}

.ai-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  min-height: 46px;
  padding: 0 12px;
  border-bottom: 1px solid var(--line);
  color: var(--ink);
  font-size: 11px;
  font-weight: 800;
  letter-spacing: 0.08em;
}

.ai-head > span {
  display: inline-flex;
  align-items: center;
  gap: 7px;
}

.ai-head svg {
  color: var(--accent);
}

.head-actions {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}

.head-actions button {
  display: grid;
  place-items: center;
  width: 28px;
  height: 28px;
  border: 0;
  background: transparent;
  color: var(--muted);
  cursor: pointer;
  transition: color 0.16s ease;
}

.head-actions button:hover {
  color: var(--ink);
}

@media (max-width: 920px) {
  .ai-drawer {
    width: 100vw;
  }
}
</style>
