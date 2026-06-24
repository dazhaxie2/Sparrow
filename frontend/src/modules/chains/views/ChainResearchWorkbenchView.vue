<template>
  <div class="research-shell">
    <AppHeader @show-graph="$router.push('/')" />
    <main class="research-page">
      <header class="research-header">
        <div class="heading">
          <router-link to="/chains"><ArrowLeft :size="15" />产业链</router-link>
          <h1>{{ detail?.card.title || '产业链深度调研' }}</h1>
          <p>{{ detail?.card.brief || '通过对话收窄问题，然后启动联网 Multi-Agent 深度调研。' }}</p>
        </div>
        <div class="run-actions">
          <div v-if="researching" class="progress-copy">
            <span>{{ stageText }}</span>
            <strong>{{ progress }}%</strong>
          </div>
          <button v-if="researching" class="secondary" type="button" @click="cancelRun">取消调研</button>
          <button v-else class="primary" type="button" :disabled="loading || starting" @click="startRun">
            <LoaderCircle v-if="starting" class="spin" :size="15" />
            <SearchCheck v-else :size="15" />
            {{ detail?.card.status === 'COMPLETED' ? '重新联网调研' : '启动联网深度调研' }}
          </button>
        </div>
      </header>

      <div v-if="loading" class="page-state"><LoaderCircle class="spin" :size="20" />正在加载工作台</div>
      <div v-else-if="error && !detail" class="page-state error">
        <AlertTriangle :size="20" />{{ error }}
        <button type="button" @click="load">重试</button>
      </div>
      <section v-else-if="detail" class="workbench-layout">
        <DialogWorkbench
          :dialog-messages="dialogMessages"
          :dialog-loading="sending || researching"
          :dialog-error="error"
          :dialog-active="Boolean(detail.graph)"
          title="CHAIN RESEARCH AGENTS"
          :status-text="researching ? stageText.toUpperCase() : detail.card.status.toUpperCase()"
          empty-title="和调研 Agent 对话"
          empty-hint="说说你关注的产品、企业、地区和时间范围。"
          :loading-label="researching ? `${stageText} · ${progress}%` : 'AGENT THINKING'"
          placeholder="补充调研范围、重点公司或想验证的问题…"
          @submit="sendMessage"
          @switch-to-map="$router.push('/chains')"
        />

        <section class="result-panel">
          <nav class="result-tabs">
            <button :class="{ active: tab === 'graph' }" type="button" @click="tab = 'graph'">
              <Network :size="15" />互动图谱
              <span>{{ detail.card.nodeCount }}</span>
            </button>
            <button :class="{ active: tab === 'report' }" type="button" @click="tab = 'report'">
              <FileText :size="15" />深度报告
            </button>
            <button :class="{ active: tab === 'sources' }" type="button" @click="tab = 'sources'">
              <BookOpenCheck :size="15" />来源
              <span>{{ detail.sources.length }}</span>
            </button>
          </nav>

          <div v-if="researching" class="run-progress"><i :style="{ width: `${progress}%` }"></i></div>
          <ResearchGraph v-if="tab === 'graph'" :graph="detail.graph" :sources="detail.sources" />
          <article v-else-if="tab === 'report'" class="report-view">
            <div v-if="detail.reportMarkdown" class="markdown" v-html="renderMarkdown(detail.reportMarkdown)"></div>
            <div v-else class="result-empty"><FileText :size="30" /><strong>报告尚未生成</strong><span>启动联网调研后，报告 Agent 会在这里交付带引用的分析。</span></div>
          </article>
          <div v-else class="source-list">
            <a v-for="source in detail.sources" :key="source.id" :href="source.url" target="_blank" rel="noreferrer" class="source-card">
              <span>{{ source.sourceRef }}</span>
              <div><strong>{{ source.title }}</strong><small>{{ source.publisher || source.url }}</small><p>{{ source.snippet }}</p></div>
              <ExternalLink :size="14" />
            </a>
            <div v-if="!detail.sources.length" class="result-empty"><BookOpenCheck :size="30" /><strong>暂无来源</strong><span>Agent 联网搜索并核验后，来源会集中列在这里。</span></div>
          </div>
        </section>
      </section>
    </main>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { AlertTriangle, ArrowLeft, BookOpenCheck, ExternalLink, FileText, LoaderCircle, Network, SearchCheck } from '@lucide/vue'
import AppHeader from '../../../app/components/AppHeader.vue'
import DialogWorkbench from '../../graph/components/DialogWorkbench.vue'
import { renderMarkdown } from '../../ai/utils/markdown'
import ResearchGraph from '../components/ResearchGraph.vue'
import {
  cancelResearchRun,
  fetchResearchCard,
  sendResearchMessage,
  startResearchRun,
  streamResearchEvents,
} from '../researchApi'
import type { ResearchCardDetail } from '../researchTypes'

const props = defineProps<{ id: number }>()
const detail = ref<ResearchCardDetail | null>(null)
const loading = ref(true)
const sending = ref(false)
const starting = ref(false)
const error = ref('')
const tab = ref<'graph' | 'report' | 'sources'>('graph')
let streamController: AbortController | null = null
let pollTimer: number | null = null

const dialogMessages = computed(() => (detail.value?.messages ?? []).map(message => ({
  id: message.id,
  role: message.role,
  title: message.agent ? message.agent.toUpperCase() : undefined,
  content: message.content,
})))
const researching = computed(() => detail.value?.card.status === 'RESEARCHING')
const progress = computed(() => detail.value?.card.progress ?? 0)
const stageText = computed(() => ({
  planning: '规划调研任务',
  searching: '联网检索资料',
  verifying: '交叉核验证据',
  mapping: '构建产业链图谱',
  writing: '撰写深度报告',
}[detail.value?.card.currentStage || ''] || '准备调研'))

async function load(silent = false) {
  if (!silent) loading.value = true
  try {
    detail.value = await fetchResearchCard(props.id)
    error.value = ''
    if (researching.value) connectEvents()
  } catch (e: any) {
    error.value = e.message || '调研工作台加载失败'
  } finally {
    loading.value = false
  }
}

async function sendMessage(content: string) {
  if (sending.value || researching.value) return
  sending.value = true
  error.value = ''
  try {
    const reply = await sendResearchMessage(props.id, content)
    if (detail.value) detail.value.messages.push(reply.userMessage, reply.assistantMessage)
  } catch (e: any) {
    error.value = e.message || '消息发送失败'
  } finally {
    sending.value = false
  }
}

async function startRun() {
  if (starting.value) return
  starting.value = true
  error.value = ''
  try {
    const result = await startResearchRun(props.id)
    if (detail.value) {
      detail.value.card.status = 'RESEARCHING'
      detail.value.card.currentStage = 'planning'
      detail.value.card.progress = 3
      detail.value.activeRun = { id: result.runId, status: 'RUNNING', currentStage: 'planning', progress: 3, errorMessage: null, startedAt: new Date().toISOString(), finishedAt: null }
    }
    connectEvents()
  } catch (e: any) {
    error.value = e.message || '启动调研失败'
  } finally {
    starting.value = false
  }
}

async function cancelRun() {
  const runId = detail.value?.activeRun?.id
  if (!runId) return
  try {
    await cancelResearchRun(props.id, runId)
    stopEvents()
    await load(true)
  } catch (e: any) {
    error.value = e.message || '取消失败'
  }
}

function applyProgress(data: Record<string, unknown>) {
  if (!detail.value) return
  if (typeof data.stage === 'string') detail.value.card.currentStage = data.stage
  if (typeof data.progress === 'number') detail.value.card.progress = data.progress
}

function connectEvents() {
  if (streamController || !researching.value) return
  const controller = new AbortController()
  streamController = controller
  void streamResearchEvents(props.id, async (event, data) => {
    applyProgress(data)
    if (event === 'completed' || event === 'failed') {
      stopEvents()
      await load(true)
      if (event === 'completed') tab.value = 'graph'
    }
  }, controller.signal).catch(() => {
    if (!controller.signal.aborted && researching.value) startPolling()
  }).finally(() => {
    if (streamController === controller) streamController = null
  })
}

function startPolling() {
  if (pollTimer != null) return
  pollTimer = window.setInterval(async () => {
    await load(true)
    if (!researching.value) stopEvents()
  }, 2500)
}

function stopEvents() {
  streamController?.abort()
  streamController = null
  if (pollTimer != null) window.clearInterval(pollTimer)
  pollTimer = null
}

watch(() => props.id, () => { stopEvents(); void load() })
onMounted(() => void load())
onUnmounted(stopEvents)
</script>

<style scoped>
.research-shell { height: 100vh; overflow: hidden; background: var(--surface); }
.research-page { display: flex; flex-direction: column; height: calc(100vh - 52px); min-height: 0; padding: 14px 18px 18px; }
.research-header { flex: none; display: flex; align-items: flex-end; justify-content: space-between; gap: 20px; padding: 4px 2px 14px; }
.heading { min-width: 0; }
.heading a { display: inline-flex; align-items: center; gap: 5px; color: var(--muted); font-size: 11px; text-decoration: none; }
.heading a:hover { color: var(--accent); }
.heading h1 { margin: 5px 0 0; font-size: 22px; }
.heading p { margin: 4px 0 0; color: var(--ink-2); font-size: 12px; }
.run-actions { flex: none; display: flex; align-items: center; gap: 10px; }
.run-actions button, .page-state button { display: inline-flex; align-items: center; gap: 7px; min-height: 36px; padding: 0 13px; border: 1px solid var(--line); border-radius: 7px; background: #fff; color: var(--ink); cursor: pointer; }
.run-actions .primary { border-color: var(--accent); background: var(--accent); color: #fff; }
.run-actions button:disabled { opacity: .55; cursor: default; }
.progress-copy { display: grid; justify-items: end; gap: 2px; font-size: 11px; color: var(--muted); }
.progress-copy strong { color: var(--accent); }
.workbench-layout { flex: 1; min-height: 0; display: flex; gap: 12px; }
.result-panel { flex: 1; min-width: 0; display: flex; flex-direction: column; border: 1px solid var(--line); border-radius: 8px; background: #fff; overflow: hidden; }
.result-tabs { flex: none; display: flex; min-height: 56px; align-items: stretch; padding: 0 12px; border-bottom: 1px solid var(--line); }
.result-tabs button { display: inline-flex; align-items: center; gap: 7px; border: 0; border-bottom: 2px solid transparent; background: transparent; padding: 0 14px; color: var(--muted); font-size: 12px; font-weight: 800; cursor: pointer; }
.result-tabs button.active { border-bottom-color: var(--accent); color: var(--ink); }
.result-tabs span { padding: 2px 6px; border-radius: 99px; background: var(--surface); font-size: 9px; }
.run-progress { flex: none; height: 3px; background: #f0f1f2; }
.run-progress i { display: block; height: 100%; background: var(--accent); transition: width .3s ease; }
.report-view, .source-list { flex: 1; min-height: 0; overflow-y: auto; padding: 24px; }
.markdown { max-width: 840px; margin: 0 auto; color: var(--ink-2); font-size: 14px; line-height: 1.85; }
.markdown :deep(h3), .markdown :deep(h4) { margin: 22px 0 8px; color: var(--ink); }
.markdown :deep(p) { margin: 0 0 11px; }
.markdown :deep(a) { color: var(--accent); }
.markdown :deep(li) { margin: 5px 0; }
.source-list { display: grid; align-content: start; gap: 10px; }
.source-card { display: grid; grid-template-columns: auto 1fr auto; gap: 12px; padding: 14px; border: 1px solid var(--line); border-radius: 8px; color: var(--ink); text-decoration: none; }
.source-card:hover { border-color: var(--accent); }
.source-card > span { align-self: start; padding: 4px 7px; border-radius: 99px; background: rgba(255,87,34,.08); color: var(--accent); font-size: 10px; font-weight: 900; }
.source-card div { min-width: 0; display: grid; gap: 4px; }
.source-card strong { font-size: 13px; }
.source-card small { color: var(--muted); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.source-card p { margin: 0; color: var(--ink-2); font-size: 11px; line-height: 1.55; }
.result-empty, .page-state { flex: 1; min-height: 300px; display: grid; place-content: center; justify-items: center; gap: 9px; color: var(--muted); text-align: center; }
.result-empty strong { color: var(--ink); }
.result-empty span { max-width: 420px; font-size: 12px; }
.page-state.error { color: var(--danger); }
.spin { animation: spin .9s linear infinite; }
@keyframes spin { to { transform: rotate(360deg); } }
@media (max-width: 920px) {
  .research-page { height: calc(100vh - 48px); overflow-y: auto; }
  .research-header { align-items: flex-start; flex-direction: column; }
  .workbench-layout { flex-direction: column; overflow: visible; }
  .result-panel { min-height: 600px; }
}
</style>
