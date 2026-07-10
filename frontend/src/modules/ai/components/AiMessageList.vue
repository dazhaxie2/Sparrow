<template>
  <div class="ai-message-shell">
    <QuestionCursor
      v-if="questionItems.length"
      :items="questionItems"
      :active-index="activeQuestionIndex"
      @select="scrollToQuestion"
    />

    <div ref="scrollRef" class="ai-messages" @scroll="onContainerScroll">
      <div
        v-for="(msg, i) in messages"
        :key="i"
        class="msg"
        :class="msg.role === 'user' ? 'user' : 'bot'"
        :data-question-order="questionOrderByMessageIndex.get(i) ?? undefined"
      >
        <div v-if="msg.role !== 'user' && (msg.mode || msg.intent)" class="msg-meta">
          <span v-if="msg.mode" class="mode-pill">{{ modeLabel(msg.mode) }}</span>
          <span v-if="msg.intent" class="intent-pill">{{ intentLabel(msg.intent) }}</span>
        </div>
        <div
          v-if="msg.role !== 'user' && msg.harness"
          class="harness-trace"
          :class="`harness-${msg.harness.status}`"
          :title="`完整追踪 ID: ${msg.harness.traceId}`"
        >
          <ShieldCheck :size="12" />
          <span>{{ harnessLabel(msg.harness.status) }}</span>
          <span v-if="msg.harness.contextMessages">上下文 {{ msg.harness.contextMessages }} 条</span>
          <code>{{ shortTrace(msg.harness.traceId) }}</code>
        </div>
        <!-- 思考过程(reasoning 模型才有):默认展开,可折叠。 -->
        <div
          v-if="msg.role !== 'user' && msg.thinking"
          class="thinking-block"
          :class="{ open: thinkingOpen[i] !== false }"
        >
          <button class="thinking-toggle" type="button" @click="toggleThinking(i)">
            <Brain :size="13" />
            <span>思考过程</span>
            <ChevronDown :size="13" class="chev" :class="{ flipped: thinkingOpen[i] !== false }" />
          </button>
          <div v-if="thinkingOpen[i] !== false" class="thinking-content">
            <span v-html="renderMessage(msg.thinking)" />
          </div>
        </div>
        <!-- 流式与完成态都走 markdown 渲染:token 已在 useChat 里按帧批处理(rAF/100ms),
             每个 delta 只触发一次重渲染,markdown 解析不会成为瓶颈。 -->
        <div class="msg-content" :class="{ 'msg-content-streaming': msg.streaming }" v-html="renderMessage(msg.content)" />
        <span v-if="msg.streaming" class="cursor" aria-hidden="true" />
        <div v-if="msg.role !== 'user' && msg.thinking && msg.steps?.length" class="agent-steps">
          <span v-for="step in msg.steps" :key="step.key" :class="`step-${step.status}`">
            {{ step.label }}
          </span>
        </div>
        <div v-if="msg.sources?.length" class="src">
          <span>来源:</span>
          <template v-for="(source, sourceIndex) in msg.sources" :key="source.id ?? sourceIndex">
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
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, reactive, ref, watch } from 'vue'
import { Brain, ChevronDown, LoaderCircle, ShieldCheck } from '@lucide/vue'
import { renderMarkdown } from '../utils/markdown'
import QuestionCursor from './QuestionCursor.vue'
import type { AiHarnessMetadata } from '../types'

interface ChatStep {
  key: string
  label: string
  status: string
}

interface ChatSource {
  id?: string | number
  name: string
  url?: string | null
}

interface ChatMessage {
  role: string
  content: string
  timestamp?: number
  thinking?: string
  mode?: string
  intent?: string
  streaming?: boolean
  steps?: ChatStep[]
  sources?: ChatSource[]
  harness?: AiHarnessMetadata
}

const props = defineProps<{
  messages: ChatMessage[]
  loading: boolean
  phase: string
}>()

const scrollRef = ref<HTMLElement | null>(null)
const activeQuestionIndex = ref(0)

// 思考过程折叠状态:按消息索引记录 true=展开 / false=收起,默认展开(流式时直观)。
const thinkingOpen = reactive<Record<number, boolean>>({})

function toggleThinking(index: number) {
  thinkingOpen[index] = thinkingOpen[index] === false
}

/** 最后一条消息是否已有可显示内容(content 或 thinking),用于决定是否显示独立的加载提示行。 */
const lastMessageHasContent = computed(() => {
  const last = props.messages[props.messages.length - 1]
  return Boolean(last && (last.content || last.thinking))
})

const questionItems = computed(() => props.messages
  .map((message, messageIndex) => ({ message, messageIndex }))
  .filter(item => item.message.role === 'user')
  .map((item, order) => ({
    id: `${item.messageIndex}-${item.message.timestamp ?? order}`,
    label: compactLabel(item.message.content),
    messageIndex: item.messageIndex,
  })))

const questionOrderByMessageIndex = computed(() => {
  const map = new Map<number, number>()
  questionItems.value.forEach((item, order) => map.set(item.messageIndex, order))
  return map
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

function harnessLabel(status: string) {
  return ({
    running: 'Harness 执行中',
    completed: 'Harness 已验证',
    degraded: 'Harness 已降级恢复',
    failed: 'Harness 失败',
  } as Record<string, string>)[status] ?? 'Harness'
}

function shortTrace(traceId: string) {
  return `#${traceId.slice(0, 8)}`
}

function compactLabel(value: string) {
  const text = value.replace(/\s+/g, ' ').trim()
  return text.length <= 80 ? text : `${text.slice(0, 80)}...`
}

function onContainerScroll() {
  // 用户主动滚动是阅读意图的唯一信号:一旦离开底部区域,就标记"正在阅读上方",
  // 流式 token 到达时不再强制拉回底部,直到用户重新滚到底部。
  userScrolledAway = !isNearBottom()
  syncActiveQuestion()
}

function syncActiveQuestion() {
  const container = scrollRef.value
  if (!container || !questionItems.value.length) {
    activeQuestionIndex.value = 0
    return
  }
  const marks = Array.from(container.querySelectorAll<HTMLElement>('[data-question-order]'))
  const currentTop = container.scrollTop + 24
  let active = 0
  for (const mark of marks) {
    if (mark.offsetTop <= currentTop) {
      active = Number(mark.dataset.questionOrder ?? 0)
    }
  }
  activeQuestionIndex.value = Math.min(active, questionItems.value.length - 1)
}

async function scrollToQuestion(index: number) {
  await nextTick()
  const container = scrollRef.value
  const target = container?.querySelector<HTMLElement>(`[data-question-order="${index}"]`)
  if (!container || !target) return
  container.scrollTo({ top: Math.max(0, target.offsetTop - 10), behavior: 'smooth' })
  activeQuestionIndex.value = index
}

// 新消息到达或流式内容增长时,自动滚动到底部。
// 仅当用户已经停留在底部附近(未主动向上阅读)时才自动滚动,
// 否则保持用户当前阅读位置,避免生成过程中视图被反复拉到底部。
const STICK_TO_BOTTOM_PX = 80
let userScrolledAway = false

function isNearBottom() {
  const el = scrollRef.value
  if (!el) return true
  return el.scrollHeight - el.scrollTop - el.clientHeight <= STICK_TO_BOTTOM_PX
}

function scrollToBottom(force = false) {
  if (!scrollRef.value) return
  // 非强制滚动时,用户已向上阅读则不打扰。
  if (!force && userScrolledAway) return
  scrollRef.value.scrollTop = scrollRef.value.scrollHeight
  userScrolledAway = false
  syncActiveQuestion()
}

// 节流滚动:流式 delta 高频到达时,每 120ms 最多滚一次,避免每个 token 都
// scrollToBottom → syncActiveQuestion → querySelectorAll 强制布局(layout thrash)。
// scrollToBottom 内部会判断 userScrolledAway,用户向上阅读时整体静默。
let scrollThrottleTimer: ReturnType<typeof setTimeout> | null = null
let scrollThrottlePending = false
function throttledScrollToBottom() {
  if (scrollThrottleTimer) {
    scrollThrottlePending = true
    return
  }
  scrollThrottleTimer = setTimeout(() => {
    scrollThrottleTimer = null
    nextTick(() => scrollToBottom())
    if (scrollThrottlePending) {
      scrollThrottlePending = false
      throttledScrollToBottom()
    }
  }, 120)
}

watch(() => props.messages.length, async () => {
  // 新消息(用户提问 / 新 assistant 占位)总是滚到底部:这是用户的主动操作,不属于"干扰阅读"。
  await nextTick()
  scrollToBottom(true)
})

watch(
  () => props.messages[props.messages.length - 1]?.content,
  () => {
    throttledScrollToBottom()
  },
)
</script>

<style scoped>
.ai-messages {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 12px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.ai-message-shell {
  flex: 1;
  min-height: 0;
  display: flex;
  background: var(--panel);
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

.harness-trace {
  display: flex;
  align-items: center;
  gap: 6px;
  width: fit-content;
  margin: -2px 0 8px;
  color: #28744f;
  font-size: 10px;
}

.harness-trace code {
  color: var(--muted);
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
}

.harness-degraded { color: #a45b16; }
.harness-failed { color: #b3261e; }

/* 流式渲染期间用纯文本插值:保留换行与空白,视觉上与 markdown 渲染后基本一致,
   避免每个 token 都全量 markdown 解析 + v-html DOM 重建。完成后由 .msg-content 接管。 */
.msg-content-streaming {
  white-space: pre-wrap;
}

.msg-content :deep(h3),
.msg-content :deep(h4) {
  margin: 0 0 6px;
  color: var(--ink);
  font-size: 13px;
  line-height: 1.35;
  font-weight: 900;
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

.src {
  display: block;
  margin-top: 7px;
  color: var(--muted);
  font-size: 12px;
}

.msg.user .src {
  color: rgba(255, 255, 255, 0.75);
}

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
  padding: 8px 9px 9px;
  border-top: 1px solid var(--line);
  color: var(--muted);
  font-size: 12px;
  line-height: 1.65;
  max-height: 200px;
  overflow-y: auto;
}

.thinking-content :deep(p) {
  margin: 0 0 4px;
}

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

.spin {
  animation: spin 0.9s linear infinite;
}

@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}
</style>
