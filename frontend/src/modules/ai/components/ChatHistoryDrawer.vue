<template>
  <transition name="drawer-fade">
    <div v-if="store.historyOpen" class="history-mask" @click.self="close">
      <aside class="history-panel" role="dialog" aria-label="历史对话">
        <header class="history-head">
          <span class="title"><History :size="14" /> 历史对话</span>
          <button type="button" class="icon-btn" title="关闭" @click="close"><X :size="15" /></button>
        </header>

        <div class="history-body">
          <button type="button" class="new-chat" @click="startNew">
            <Plus :size="14" /> 新建对话
          </button>

          <div v-if="store.sessionsLoading" class="empty">加载中…</div>
          <div v-else-if="store.sessionsError" class="empty error-state">
            <span>{{ store.sessionsError }}</span>
            <button type="button" class="retry-btn" @click="store.loadSessions()">
              <RefreshCw :size="12" /> 重试
            </button>
          </div>
          <div v-else-if="!store.sessions.length" class="empty">还没有历史对话</div>

          <ul v-else class="session-list">
            <li
              v-for="s in store.sessions"
              :key="s.id"
              class="session-item"
              :class="{ active: s.id === store.activeSessionId }"
              @click="select(s.id)"
            >
              <div class="session-main">
                <span class="session-title">{{ s.title }}</span>
                <span class="session-meta">
                  <span>{{ relativeTime(s.updatedAt ?? s.createdAt) }}</span>
                  <span v-if="s.messageCount" class="dot">·</span>
                  <span v-if="s.messageCount">{{ s.messageCount }} 条</span>
                </span>
              </div>
              <button
                type="button"
                class="del-btn"
                title="删除"
                @click.stop="askDelete(s)"
              >
                <Trash2 :size="13" />
              </button>
            </li>
          </ul>
        </div>

        <!-- 删除确认 -->
        <transition name="dialog-fade">
          <div v-if="pendingDelete" class="confirm-mask" @click.self="!deleting && (pendingDelete = null)">
            <div class="confirm-dialog">
              <p class="confirm-text">删除这个对话?删除后无法恢复。</p>
              <div v-if="deleteError" class="confirm-error">{{ deleteError }}</div>
              <div class="confirm-actions">
                <button type="button" class="btn-ghost" :disabled="deleting" @click="pendingDelete = null">取消</button>
                <button type="button" class="btn-danger" :disabled="deleting" @click="confirmDelete">
                  {{ deleting ? '删除中…' : '删除' }}
                </button>
              </div>
            </div>
          </div>
        </transition>
      </aside>
    </div>
  </transition>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { History, Plus, RefreshCw, Trash2, X } from '@lucide/vue'
import type { ChatSession } from '../types'
import { useChatStore } from '../store/chat'

const store = useChatStore()
const emit = defineEmits<{ (e: 'select'): void }>()

const pendingDelete = ref<ChatSession | null>(null)
/** 删除请求进行中(防重复点击 + 控制按钮禁用态)。 */
const deleting = ref(false)
/** 删除失败提示文案(在确认弹窗内联显示,3.6s 后自动清除)。 */
const deleteError = ref<string | null>(null)
let deleteErrorTimer: ReturnType<typeof setTimeout> | null = null

function close() {
  store.historyOpen = false
}

/** 新建对话:清激活会话 + 关闭抽屉,让 useChat 回到欢迎页。 */
function startNew() {
  store.startNewSession()
  store.historyOpen = false
  emit('select')
}

/**
 * 选择会话:在 store 里切换激活态(setActive 只改 id 不拉消息),
 * 通知父组件加载消息渲染(父组件调 loadHistory,只拉一次),最后关闭抽屉。
 */
function select(id: number) {
  if (id === store.activeSessionId) {
    close()
    return
  }
  store.setActive(id)
  emit('select')
  close()
}

function askDelete(s: ChatSession) {
  pendingDelete.value = s
  deleteError.value = null
}

async function confirmDelete() {
  const target = pendingDelete.value
  if (!target || deleting.value) return
  deleting.value = true
  deleteError.value = null
  const ok = await store.removeSession(target.id)
  deleting.value = false
  if (ok) {
    pendingDelete.value = null
    // 删除的是当前激活会话:清回欢迎页
    emit('select')
  } else {
    // 删除失败:保留弹窗,内联提示失败原因,不关弹窗(让用户可重试或取消)。
    deleteError.value = '删除失败，请稍后重试。'
    if (deleteErrorTimer) clearTimeout(deleteErrorTimer)
    deleteErrorTimer = setTimeout(() => { deleteError.value = null }, 3600)
  }
}

/** 相对时间格式化:N 分钟前 / N 小时前 / N 天前 / 超过一周显示日期。 */
function relativeTime(ts: number): string {
  const diff = Date.now() - ts
  const min = Math.floor(diff / 60_000)
  if (min < 1) return '刚刚'
  if (min < 60) return `${min} 分钟前`
  const hr = Math.floor(min / 60)
  if (hr < 24) return `${hr} 小时前`
  const day = Math.floor(hr / 24)
  if (day < 7) return `${day} 天前`
  const d = new Date(ts)
  return `${d.getMonth() + 1}月${d.getDate()}日`
}
</script>

<style scoped>
.history-mask {
  position: absolute;
  inset: 0;
  z-index: 20;
  background: rgba(0, 0, 0, 0.28);
  display: flex;
  justify-content: flex-end;
  backdrop-filter: blur(1px);
}

.history-panel {
  width: 78%;
  min-width: 240px;
  max-width: 340px;
  height: 100%;
  display: flex;
  flex-direction: column;
  background: var(--panel);
  border-left: 1px solid var(--line);
  box-shadow: var(--shadow-md);
  position: relative;
}

.history-head {
  flex: none;
  display: flex;
  align-items: center;
  justify-content: space-between;
  min-height: 46px;
  padding: 0 10px 0 12px;
  border-bottom: 1px solid var(--line);
  color: var(--ink);
  font-size: 11px;
  font-weight: 800;
  letter-spacing: 0.08em;
}
.history-head .title {
  display: inline-flex;
  align-items: center;
  gap: 7px;
}
.history-head svg {
  color: var(--accent);
}
.icon-btn {
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
.icon-btn:hover {
  color: var(--ink);
}

.history-body {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 10px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.new-chat {
  flex: none;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  padding: 8px;
  border: 1px dashed var(--line-strong);
  background: transparent;
  color: var(--ink-2);
  font-size: 12px;
  font-weight: 700;
  cursor: pointer;
  transition: border-color 0.16s ease, color 0.16s ease, background 0.16s ease;
}
.new-chat:hover {
  border-color: var(--accent);
  color: var(--accent);
  background: var(--accent-soft);
}

.empty {
  margin-top: 24px;
  text-align: center;
  color: var(--muted);
  font-size: 12px;
}

/* 加载失败态:错误文案 + 重试按钮,区分"加载失败"和"确实为空" */
.empty.error-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 10px;
  color: var(--danger);
}
.retry-btn {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 4px 10px;
  border: 1px solid var(--line-strong);
  border-radius: var(--radius-sm);
  background: transparent;
  color: var(--ink-2);
  font-size: 11px;
  font-weight: 600;
  cursor: pointer;
  transition: border-color 0.14s ease, color 0.14s ease;
}
.retry-btn:hover {
  border-color: var(--accent);
  color: var(--accent);
}

.session-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.session-item {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 8px 9px;
  border: 1px solid transparent;
  border-radius: var(--radius-sm);
  cursor: pointer;
  transition: background 0.14s ease, border-color 0.14s ease;
}
.session-item:hover {
  background: var(--surface);
}
.session-item.active {
  background: var(--surface);
  border-color: var(--accent);
  box-shadow: inset 3px 0 0 var(--accent);
}

.session-main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.session-title {
  font-size: 12.5px;
  color: var(--ink);
  font-weight: 600;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.session-meta {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 10.5px;
  color: var(--muted);
}
.session-meta .dot {
  opacity: 0.6;
}

.del-btn {
  flex: none;
  display: grid;
  place-items: center;
  width: 22px;
  height: 22px;
  border: 0;
  background: transparent;
  color: var(--muted);
  opacity: 0;
  cursor: pointer;
  transition: opacity 0.14s ease, color 0.14s ease;
}
.session-item:hover .del-btn,
.session-item.active .del-btn {
  opacity: 1;
}
.del-btn:hover {
  color: var(--danger);
}

/* 删除确认弹窗 */
.confirm-mask {
  position: absolute;
  inset: 0;
  z-index: 30;
  background: rgba(0, 0, 0, 0.4);
  display: grid;
  place-items: center;
  padding: 16px;
}
.confirm-dialog {
  width: 100%;
  max-width: 280px;
  background: var(--bg);
  border: 1px solid var(--line);
  border-radius: var(--radius);
  box-shadow: var(--shadow-md);
  padding: 16px;
}
.confirm-text {
  margin: 0 0 14px;
  font-size: 13px;
  color: var(--ink);
  line-height: 1.5;
}
/* 删除失败内联提示:复用错误态配色,引导用户重试或取消。 */
.confirm-error {
  margin: -6px 0 12px;
  padding: 6px 8px;
  border: 1px solid var(--danger);
  border-radius: var(--radius-sm);
  background: rgba(220, 38, 38, 0.06);
  color: var(--danger);
  font-size: 12px;
  line-height: 1.5;
}
.confirm-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}
.btn-ghost,
.btn-danger {
  padding: 6px 14px;
  border: 1px solid var(--line-strong);
  background: transparent;
  font-size: 12px;
  font-weight: 700;
  cursor: pointer;
  transition: background 0.14s ease, color 0.14s ease, border-color 0.14s ease;
}
.btn-ghost {
  color: var(--ink-2);
}
.btn-ghost:hover {
  background: var(--surface);
}
.btn-danger {
  border-color: var(--danger);
  color: var(--danger);
}
.btn-danger:hover {
  background: var(--danger);
  color: var(--bg);
}
.btn-ghost:disabled,
.btn-danger:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
.btn-danger:disabled:hover {
  background: transparent;
  color: var(--danger);
}

/* 过渡动画 */
.drawer-fade-enter-active,
.drawer-fade-leave-active {
  transition: opacity 0.18s ease;
}
.drawer-fade-enter-from,
.drawer-fade-leave-to {
  opacity: 0;
}
.drawer-fade-enter-active .history-panel,
.drawer-fade-leave-active .history-panel {
  transition: transform 0.22s ease;
}
.drawer-fade-enter-from .history-panel,
.drawer-fade-leave-to .history-panel {
  transform: translateX(100%);
}
.dialog-fade-enter-active,
.dialog-fade-leave-active {
  transition: opacity 0.14s ease;
}
.dialog-fade-enter-from,
.dialog-fade-leave-to {
  opacity: 0;
}
</style>
