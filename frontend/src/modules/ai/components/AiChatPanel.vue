<template>
  <!-- 折叠态：右侧窄竖条把手 -->
  <button v-if="collapsed" class="ai-rail-collapsed" type="button" title="展开 AI 对话" @click="$emit('toggle')">
    <Bot :size="16" />
    <span class="rail-text">AI 对话</span>
  </button>

  <!-- 展开态：常驻竖栏 -->
  <section v-else class="ai-rail-panel">
    <header class="rail-head">
      <span><Sparkles :size="14" /> AI 对话</span>
      <div class="head-actions">
        <button type="button" title="清空对话" @click="clearMessages"><Trash2 :size="14" /></button>
        <button type="button" title="收起" @click="$emit('toggle')"><PanelRightClose :size="15" /></button>
      </div>
    </header>

    <p v-if="contextNode" class="rail-context">
      围绕「<strong>{{ contextNode.name }}</strong>」提问
    </p>

    <AiMessageList :messages="messages" :loading="loading" :phase="phase" />

    <AiComposer :context-node="contextNode" :loading="loading" @submit="handleSubmit" />
  </section>
</template>

<script setup lang="ts">
import { PanelRightClose, Sparkles, Trash2, Bot } from '@lucide/vue'
import { useUserStore } from '../../user/store'
import { useChat } from '../composables/useChat'
import type { NodeBrief } from '../../graph/types'
import AiMessageList from './AiMessageList.vue'
import AiComposer from './AiComposer.vue'

const props = defineProps<{
  /** 当前上下文节点(科技树节点 / 产业链标题伪节点)，提问会自动带前缀；null 表示无上下文。 */
  contextNode: NodeBrief | null
  /** 折叠为窄竖条。 */
  collapsed?: boolean
}>()

defineEmits<{ (e: 'toggle'): void }>()

const user = useUserStore()
const { messages, loading, phase, ask, clearMessages } = useChat()

/** 提交一个问题：带上当前节点上下文，交给真 AI。 */
async function handleSubmit(text: string) {
  const question = text.trim()
  if (!question) return
  const contextualQuestion = props.contextNode
    ? `围绕「${props.contextNode.name}」回答：${question}`
    : question
  await ask(contextualQuestion, user.isLoggedIn())
}
</script>

<style scoped>
/* 折叠窄竖条 */
.ai-rail-collapsed {
  width: 100%;
  height: 100%;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 10px;
  padding: 14px 0;
  border: 0;
  border-left: 1px solid var(--ink);
  background: var(--ink);
  color: var(--bg);
  cursor: pointer;
  transition: padding 0.16s ease, background 0.16s ease;
}
.ai-rail-collapsed:hover { padding: 18px 0; background: #000; }
.rail-text { writing-mode: vertical-rl; letter-spacing: 0.12em; font-size: 12px; font-weight: 800; }

/* 展开竖栏 */
.ai-rail-panel {
  height: 100%;
  min-height: 0;
  display: flex;
  flex-direction: column;
  background: var(--panel);
  border-left: 1px solid var(--line);
  overflow: hidden;
}
.rail-head {
  flex: none;
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
.rail-head > span { display: inline-flex; align-items: center; gap: 7px; }
.rail-head svg { color: var(--accent); }
.head-actions { display: inline-flex; align-items: center; gap: 4px; }
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
.head-actions button:hover { color: var(--ink); }
.rail-context {
  flex: none;
  margin: 0;
  padding: 8px 12px;
  border-bottom: 1px solid var(--line);
  background: rgba(255, 87, 34, 0.05);
  color: var(--ink-2);
  font-size: 12px;
}
.rail-context strong { color: var(--accent); }
</style>
