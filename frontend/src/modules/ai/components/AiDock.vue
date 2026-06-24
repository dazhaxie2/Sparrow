<template>
  <div ref="dockRef" class="ai-dock" :class="{ collapsed, dragging }" :style="dockStyle">
    <button
      class="ai-toggle"
      type="button"
      :title="collapsed ? '打开 AI 向导' : '拖拽或收起 AI 向导'"
      @pointerdown="startDrag"
      @click="toggleDock"
    >
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
          <div v-if="msg.role !== 'user' && (msg.mode || msg.intent)" class="msg-meta">
            <span v-if="msg.mode" class="mode-pill">{{ modeLabel(msg.mode) }}</span>
            <span v-if="msg.intent" class="intent-pill">{{ intentLabel(msg.intent) }}</span>
          </div>
          <!-- 思考过程(reasoning 模型才有):默认折叠,可展开查看。流式生成时自动展开。 -->
          <div v-if="msg.role !== 'user' && msg.thinking" class="thinking-block" :class="{ open: thinkingOpen[i] !== false }">
            <button class="thinking-toggle" type="button" @click="toggleThinking(i)">
              <Brain :size="13" />
              <span>思考过程</span>
              <ChevronDown :size="13" class="chev" :class="{ flipped: thinkingOpen[i] !== false }" />
            </button>
            <div v-if="thinkingOpen[i] !== false" class="thinking-content" v-html="renderMessage(msg.thinking)" />
          </div>
          <div class="msg-content" v-html="renderMessage(msg.content)" />
          <span v-if="msg.streaming" class="cursor" aria-hidden="true" />
          <div v-if="msg.role !== 'user' && msg.steps?.length" class="agent-steps">
            <span v-for="step in msg.steps" :key="step.key" :class="`step-${step.status}`">
              {{ step.label }}
            </span>
          </div>
          <div v-if="msg.sources?.length" class="src">
            <span>来源:</span>
            <template v-for="(source, sourceIndex) in msg.sources" :key="source.id">
              <a v-if="source.url" :href="source.url" target="_blank" rel="noreferrer">{{ source.name }}</a>
              <span v-else>{{ source.name }}</span>
              <span v-if="sourceIndex < msg.sources.length - 1">、</span>
            </template>
          </div>
        </div>

        <!-- 初始检索阶段(占位消息尚未产生任何内容时)显示加载提示。 -->
        <div v-if="loading && !lastMessageHasContent" class="msg bot typing">
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
import { computed, nextTick, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { Bot, Brain, ChevronDown, LoaderCircle, Send, Sparkles, Trash2 } from '@lucide/vue'
import { useChat } from '../composables/useChat'
import { renderMarkdown } from '../utils/markdown'
import { useUserStore } from '../../user/store'
import type { NodeBrief } from '../../graph/types'

const props = defineProps<{ contextNode: NodeBrief | null }>()

const user = useUserStore()
const { messages, loading, phase, ask, clearMessages } = useChat()
const collapsed = ref(true)
const input = ref('')
const messagesRef = ref<HTMLElement | null>(null)
const dockRef = ref<HTMLElement | null>(null)
const positioned = ref(false)
const dragging = ref(false)
const position = ref({ x: 0, y: 0 })

// 思考过程折叠状态:按消息索引记录 true=展开 / false=收起,默认展开(流式时直观)。
// 用 reactive 对象而非 ref,以便动态键的增删保持响应式。
const thinkingOpen = reactive<Record<number, boolean>>({})

function toggleThinking(index: number) {
  thinkingOpen[index] = thinkingOpen[index] === false
}

/** 最后一条消息是否已有可显示内容(content 或 thinking),用于决定是否显示独立的加载提示行。 */
const lastMessageHasContent = computed(() => {
  const last = messages.value[messages.value.length - 1]
  return Boolean(last && (last.content || last.thinking))
})

const DOCK_POSITION_KEY = 'sparrow_ai_dock_position'
const EDGE_GAP = 12
const BALL_SIZE = 56
const PANEL_WIDTH = 390
const PANEL_HEIGHT = 520

let suppressNextToggle = false
let dragState: {
  pointerId: number
  startX: number
  startY: number
  dockX: number
  dockY: number
  moved: boolean
} | null = null

const quickPrompts = computed(() => {
  if (!props.contextNode) return ['怎么开始学习？', '推荐一条路线', '哪些节点最关键？']
  return ['它依赖什么？', '为什么重要？', '推荐下一步学什么？']
})

const modeLabels: Record<string, string> = {
  agent: 'Agent',
  rag: 'RAG',
  rules: '规则',
  guide: '向导',
  error: '提示',
}

const intentLabels: Record<string, string> = {
  dependency: '依赖关系',
  learning_path: '学习路径',
  why: '原理解释',
  compare: '对比分析',
  general: '综合问答',
}

function renderMessage(content: string) {
  return renderMarkdown(content)
}

function modeLabel(mode: string) {
  return modeLabels[mode] ?? mode
}

function intentLabel(intent: string) {
  return intentLabels[intent] ?? intent
}

const dockStyle = computed(() => ({
  transform: `translate3d(${position.value.x}px, ${position.value.y}px, 0)`,
  opacity: positioned.value ? '1' : '0',
}))

function currentDockSize() {
  const fallback = collapsed.value
    ? { width: BALL_SIZE, height: BALL_SIZE }
    : { width: Math.min(PANEL_WIDTH, window.innerWidth - EDGE_GAP * 2), height: PANEL_HEIGHT }
  return {
    width: dockRef.value?.offsetWidth || fallback.width,
    height: dockRef.value?.offsetHeight || fallback.height,
  }
}

function clampPosition(next = position.value) {
  const { width, height } = currentDockSize()
  return {
    x: Math.min(Math.max(EDGE_GAP, next.x), Math.max(EDGE_GAP, window.innerWidth - width - EDGE_GAP)),
    y: Math.min(Math.max(EDGE_GAP, next.y), Math.max(EDGE_GAP, window.innerHeight - height - EDGE_GAP)),
  }
}

function defaultPosition() {
  return {
    x: window.innerWidth - BALL_SIZE - EDGE_GAP,
    y: Math.round(window.innerHeight * 0.58 - BALL_SIZE / 2),
  }
}

function restorePosition() {
  try {
    const raw = localStorage.getItem(DOCK_POSITION_KEY)
    if (raw) {
      const saved = JSON.parse(raw)
      if (Number.isFinite(saved?.x) && Number.isFinite(saved?.y)) {
        position.value = clampPosition({ x: saved.x, y: saved.y })
        return
      }
    }
  } catch {
    // Ignore stale or malformed coordinates.
  }
  position.value = clampPosition(defaultPosition())
}

function savePosition() {
  localStorage.setItem(DOCK_POSITION_KEY, JSON.stringify(position.value))
}

async function setCollapsed(value: boolean) {
  if (collapsed.value === value) return
  const wasCollapsed = collapsed.value
  const previous = currentDockSize()
  collapsed.value = value
  await nextTick()

  let next = position.value
  if (wasCollapsed && !value) {
    next = {
      ...next,
      x: Math.min(next.x, window.innerWidth - currentDockSize().width - EDGE_GAP),
    }
  } else if (!wasCollapsed && value && position.value.x + previous.width >= window.innerWidth - 72) {
    next = {
      ...next,
      x: window.innerWidth - BALL_SIZE - EDGE_GAP,
    }
  }

  position.value = clampPosition(next)
  savePosition()
}

function toggleDock() {
  if (suppressNextToggle) {
    suppressNextToggle = false
    return
  }
  void setCollapsed(!collapsed.value)
}

function startDrag(event: PointerEvent) {
  if (event.button !== 0) return
  dragState = {
    pointerId: event.pointerId,
    startX: event.clientX,
    startY: event.clientY,
    dockX: position.value.x,
    dockY: position.value.y,
    moved: false,
  }
  dragging.value = true
  ;(event.currentTarget as HTMLElement).setPointerCapture(event.pointerId)
  window.addEventListener('pointermove', handleDragMove)
  window.addEventListener('pointerup', stopDrag)
  window.addEventListener('pointercancel', stopDrag)
  event.preventDefault()
}

function handleDragMove(event: PointerEvent) {
  if (!dragState) return
  const dx = event.clientX - dragState.startX
  const dy = event.clientY - dragState.startY
  if (Math.abs(dx) + Math.abs(dy) > 4) dragState.moved = true
  position.value = clampPosition({
    x: dragState.dockX + dx,
    y: dragState.dockY + dy,
  })
  event.preventDefault()
}

function stopDrag(event: PointerEvent) {
  if (!dragState) return
  if (dragState.moved) suppressNextToggle = true
  dragState = null
  dragging.value = false
  window.removeEventListener('pointermove', handleDragMove)
  window.removeEventListener('pointerup', stopDrag)
  window.removeEventListener('pointercancel', stopDrag)
  savePosition()
  event.preventDefault()
}

function handleViewportResize() {
  position.value = clampPosition()
  savePosition()
}

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
  void setCollapsed(false)
}

function close() {
  void setCollapsed(true)
}

watch(collapsed, async () => {
  await nextTick()
  position.value = clampPosition()
})

onMounted(async () => {
  await nextTick()
  restorePosition()
  positioned.value = true
  window.addEventListener('resize', handleViewportResize)
})

onUnmounted(() => {
  window.removeEventListener('resize', handleViewportResize)
  window.removeEventListener('pointermove', handleDragMove)
  window.removeEventListener('pointerup', stopDrag)
  window.removeEventListener('pointercancel', stopDrag)
})

defineExpose({ open, close })
</script>

<style scoped>
.ai-dock {
  position: fixed;
  top: 0;
  left: 0;
  width: min(390px, calc(100vw - 24px));
  background: var(--panel);
  border: 1px solid var(--ink);
  box-shadow: var(--shadow-md);
  z-index: 50;
  will-change: transform;
  transition: opacity 0.12s ease;
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
  cursor: grab;
  touch-action: none;
  user-select: none;
}

.ai-dock.dragging .ai-toggle {
  cursor: grabbing;
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
  width: 56px;
  height: 56px;
  overflow: hidden;
  border-radius: 999px;
  box-shadow: 0 12px 32px rgba(0, 0, 0, 0.22);
}

.ai-dock.collapsed .ai-toggle {
  width: 56px;
  height: 56px;
  min-height: 56px;
  display: grid;
  grid-template-columns: 1fr;
  place-items: center;
  border: 0;
  border-bottom: 0;
  border-radius: 999px;
  background: var(--ink);
  color: var(--bg);
  padding: 0;
}

.ai-dock.collapsed .toggle-mark {
  width: 56px;
  height: 56px;
  background: transparent;
}

.ai-dock.collapsed .toggle-copy,
.ai-dock.collapsed .toggle-state {
  display: none;
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

.msg-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-bottom: 8px;
}

.mode-pill,
.intent-pill {
  display: inline-flex;
  align-items: center;
  min-height: 20px;
  border: 1px solid var(--line-strong);
  background: var(--panel);
  color: var(--ink-2);
  padding: 0 7px;
  font-size: 11px;
  font-weight: 800;
  letter-spacing: 0.04em;
}

.mode-pill {
  border-color: rgba(255, 87, 34, 0.38);
  color: var(--accent);
}

.msg-content :deep(h3),
.msg-content :deep(h4) {
  margin: 0 0 6px;
  color: var(--ink);
  font-size: 13px;
  line-height: 1.35;
  font-weight: 900;
  letter-spacing: 0;
}

.msg-content :deep(h3:not(:first-child)),
.msg-content :deep(h4:not(:first-child)) {
  margin-top: 12px;
}

.msg-content :deep(p) {
  margin: 0 0 8px;
}

.msg-content :deep(p:last-child) {
  margin-bottom: 0;
}

.msg-content :deep(ul),
.msg-content :deep(ol) {
  margin: 0 0 8px;
  padding-left: 18px;
}

.msg-content :deep(li) {
  margin: 2px 0;
}

.msg-content :deep(strong) {
  font-weight: 900;
}

.msg-content :deep(code) {
  border: 1px solid var(--line);
  background: var(--panel);
  padding: 1px 4px;
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  font-size: 12px;
}

.msg-content :deep(a),
.src a {
  color: var(--accent);
  text-decoration: none;
  border-bottom: 1px solid rgba(255, 87, 34, 0.3);
}

.msg.user .msg-content :deep(h3),
.msg.user .msg-content :deep(h4),
.msg.user .msg-content :deep(a) {
  color: var(--bg);
}

.agent-steps {
  display: flex;
  flex-wrap: wrap;
  gap: 5px;
  margin-top: 9px;
}

.agent-steps span {
  border: 1px solid var(--line);
  background: var(--panel);
  color: var(--muted);
  padding: 2px 6px;
  font-size: 11px;
  line-height: 1.4;
}

.agent-steps .step-done {
  color: var(--ink-2);
}

.agent-steps .step-partial {
  border-color: rgba(255, 87, 34, 0.34);
  color: var(--accent);
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

/* 思考过程折叠区块 */
.thinking-block {
  margin-bottom: 9px;
  border: 1px solid var(--line);
  border-radius: 6px;
  background: var(--panel);
  overflow: hidden;
}

.thinking-toggle {
  width: 100%;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 6px 9px;
  border: 0;
  background: transparent;
  color: var(--ink-2);
  font-size: 11px;
  font-weight: 800;
  letter-spacing: 0.04em;
  cursor: pointer;
}

.thinking-toggle svg {
  color: var(--muted);
}

.thinking-toggle .chev {
  margin-left: auto;
  transition: transform 0.16s ease;
}

.thinking-toggle .chev.flipped {
  transform: rotate(180deg);
}

.thinking-content {
  padding: 0 9px 9px;
  border-top: 1px solid var(--line);
  margin-top: 2px;
  padding-top: 8px;
  color: var(--muted);
  font-size: 12px;
  line-height: 1.65;
  max-height: 200px;
  overflow-y: auto;
}

.thinking-content :deep(h3),
.thinking-content :deep(h4),
.thinking-content :deep(p) {
  margin: 0 0 4px;
}

/* 流式光标 */
.cursor {
  display: inline-block;
  width: 7px;
  height: 14px;
  margin-left: 2px;
  vertical-align: text-bottom;
  background: var(--accent);
  animation: blink 0.9s steps(2, start) infinite;
}

@keyframes blink {
  0%, 50% {
    opacity: 1;
  }
  50.01%, 100% {
    opacity: 0;
  }
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
    width: calc(100vw - 32px);
  }

  .ai-dock.collapsed {
    width: 56px;
  }
}
</style>
