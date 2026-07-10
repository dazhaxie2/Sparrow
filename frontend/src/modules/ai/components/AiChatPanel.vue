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
        <button
          v-if="user.isLoggedIn()"
          type="button"
          title="历史对话"
          @click="chatStore.historyOpen = true"
        >
          <History :size="14" />
        </button>
        <button type="button" title="清空对话" @click="clearMessages"><Trash2 :size="14" /></button>
        <button type="button" title="收起" @click="$emit('toggle')"><PanelRightClose :size="15" /></button>
      </div>
    </header>

    <p v-if="contextNode" class="rail-context">
      围绕「<strong>{{ contextNode.name }}</strong>」提问
    </p>

    <AiMessageList :messages="messages" :loading="loading" :phase="phase" />

    <AiComposer :context-node="contextNode" :loading="loading" @submit="handleSubmit" />

    <!-- 历史对话抽屉(绝对定位浮层,覆盖在面板上) -->
    <ChatHistoryDrawer @select="handleHistorySelect" />
  </section>
</template>

<script setup lang="ts">
import { onMounted, watch } from 'vue'
import { History, PanelRightClose, Sparkles, Trash2, Bot } from '@lucide/vue'
import { useUserStore } from '../../user/store'
import { useChat } from '../composables/useChat'
import type { NodeBrief } from '../../graph/types'
import AiMessageList from './AiMessageList.vue'
import AiComposer from './AiComposer.vue'
import ChatHistoryDrawer from './ChatHistoryDrawer.vue'

const props = defineProps<{
  /** 当前上下文节点(科技树节点 / 产业链标题伪节点)，提问会自动带前缀；null 表示无上下文。 */
  contextNode: NodeBrief | null
  /** 折叠为窄竖条。 */
  collapsed?: boolean
}>()

defineEmits<{ (e: 'toggle'): void }>()

const user = useUserStore()
const { messages, loading, phase, ask, clearMessages, loadHistory, store: chatStore } = useChat()

// 注意:本组件并未被 <keep-alive> 缓存(keep-alive 只包 <router-view>),
// 父组件用 v-if 控制可见性时会销毁/重建本组件,导致 useChat 的 messages 本地
// ref 重置为欢迎页。因此重新挂载时,若单例 store 仍有激活会话,需从后端拉回
// 历史消息恢复对话,避免"收起再展开后历史消失"。
onMounted(async () => {
  if (!user.isLoggedIn()) return
  chatStore.loadSessions()
  // 重新挂载(收起侧栏、切换 tab)时 activeSessionId 仍在 store 里,重新拉回该会话消息。
  if (chatStore.activeSessionId !== null) {
    await loadHistory(chatStore.activeSessionId)
  }
})

// 监听登录态:登录(false→true)刷新历史;登出(true→false)清屏回欢迎页。
// 登出时 chatStore.reset() 已清会话列表,这里再清当前消息,让界面立即归零。
watch(
  () => user.isLoggedIn(),
  (loggedIn, wasLoggedIn) => {
    if (loggedIn && !wasLoggedIn) chatStore.loadSessions()
    if (!loggedIn && wasLoggedIn) clearMessages()
  },
)

/** 提交一个问题：带上当前节点上下文，交给真 AI。 */
async function handleSubmit(text: string) {
  const question = text.trim()
  if (!question) return
  const contextualQuestion = props.contextNode
    ? `围绕「${props.contextNode.name}」回答：${question}`
    : question
  await ask(contextualQuestion, user.isLoggedIn())
}

/**
 * 历史抽屉 select 事件:用户点了某个会话或"新建对话"或"删除了当前会话"。
 * 切换/删除当前会话时,本地消息要同步刷新(loadHistory 会处理:有 session 加载历史,无则回欢迎页)。
 */
async function handleHistorySelect() {
  if (chatStore.activeSessionId !== null) {
    await loadHistory(chatStore.activeSessionId)
  } else {
    // 无激活会话(新建对话/删除了当前):回到欢迎页
    clearMessages()
  }
}
</script>

<style scoped>
/* 折叠窄竖条：低调浅色，避免纯黑在浅色界面里突兀；透明背景继承父容器底色 */
.ai-rail-collapsed {
  width: 100%;
  height: 100%;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 10px;
  padding: 14px 0;
  border: 0;
  background: transparent;
  color: var(--muted);
  cursor: pointer;
  transition: padding 0.16s ease, background 0.16s ease, color 0.16s ease;
}
.ai-rail-collapsed:hover { padding: 18px 0; background: var(--surface); color: var(--accent); }
.rail-text { writing-mode: vertical-rl; letter-spacing: 0.12em; font-size: 12px; font-weight: 700; }

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
