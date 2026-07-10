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
          <button
            v-if="!researching && detail?.card.status === 'FAILED'"
            class="secondary"
            type="button"
            :disabled="loading || starting"
            @click="startRun"
          >
            重新开始
          </button>
          <button v-if="!researching" class="primary" type="button" :disabled="loading || starting" @click="runPrimaryAction">
            <LoaderCircle v-if="starting" class="spin" :size="15" />
            <SearchCheck v-else :size="15" />
            {{ detail?.card.status === 'FAILED' ? '从中断点继续' : detail?.card.status === 'COMPLETED' ? '重新联网调研' : '启动联网深度调研' }}
          </button>
        </div>
      </header>

      <div v-if="loading" class="page-state"><LoaderCircle class="spin" :size="20" />正在加载工作台</div>
      <div v-else-if="error && !detail" class="page-state error">
        <AlertTriangle :size="20" />{{ error }}
        <button type="button" @click="load()">重试</button>
      </div>
      <section v-else-if="detail" class="workbench-layout">
        <section class="result-panel">
          <nav class="result-tabs">
            <button :class="{ active: tab === 'graph' }" type="button" @click="tab = 'graph'">
              <Network :size="15" />互动图谱
              <span>{{ detail.card.nodeCount }}</span>
            </button>
            <button :class="{ active: tab === 'report' }" type="button" @click="tab = 'report'">
              <FileText :size="15" />深度报告
            </button>
            <button :class="{ active: tab === 'forum' }" type="button" @click="tab = 'forum'">
              <MessagesSquare :size="15" />调研过程
            </button>
            <button :class="{ active: tab === 'sources' }" type="button" @click="tab = 'sources'">
              <BookOpenCheck :size="15" />来源
              <span>{{ detail.sources.length }}</span>
            </button>
            <button :class="{ active: tab === 'attachments' }" type="button" @click="tab = 'attachments'">
              <Upload :size="15" />资料
              <span>{{ detail.attachments?.length || 0 }}</span>
            </button>
          </nav>

          <div v-if="researching" class="run-progress"><i :style="{ width: `${progress}%` }"></i></div>
          <ResearchGraph v-if="tab === 'graph'" :graph="detail.graph" :sources="detail.sources" />
          <article v-else-if="tab === 'report'" class="report-view">
            <div v-if="detail.reportIr" class="report-toolbar">
              <button class="export-btn" type="button" :disabled="exporting" @click="exportReportPdf">
                <LoaderCircle v-if="exporting" class="spin" :size="14" />
                <FileDown v-else :size="14" />导出 PDF
              </button>
            </div>
            <RichReport
              v-if="detail.reportIr"
              ref="richReportRef"
              :report="detail.reportIr"
              :sources="detail.sources"
              @source-click="jumpToSource"
            />
            <div v-else-if="detail.reportMarkdown" class="markdown" v-html="renderMarkdown(detail.reportMarkdown)"></div>
            <div v-else class="result-empty"><FileText :size="30" /><strong>报告尚未生成</strong><span>启动联网调研后，报告 Agent 会在这里交付带引用的分析。</span></div>
          </article>
          <AgentForum
            v-else-if="tab === 'forum'"
            :researching="researching"
            :events="forumEvents"
            :error="detail.card.status === 'FAILED' ? detail.card.lastError : null"
          />
          <div v-else-if="tab === 'sources'" class="source-list">
            <a v-for="source in detail.sources" :key="source.id" :href="source.url" target="_blank" rel="noreferrer" class="source-card">
              <span>{{ source.sourceRef }}</span>
              <div><strong>{{ source.title }}</strong><small>{{ source.publisher || source.url }}</small><p>{{ source.snippet }}</p></div>
              <ExternalLink :size="14" />
            </a>
            <div v-if="!detail.sources.length" class="result-empty"><BookOpenCheck :size="30" /><strong>暂无来源</strong><span>Agent 联网搜索并核验后，来源会集中列在这里。</span></div>
          </div>

          <div v-if="tab === 'attachments'" class="attachment-panel">
            <div class="attachment-upload">
              <div class="upload-area" @click="triggerFileInput" @dragover.prevent @drop.prevent="handleDrop">
                <Upload v-if="!uploading" :size="24" />
                <LoaderCircle v-else class="spin" :size="24" />
                <span>{{ uploading ? '上传中...' : '点击或拖拽上传 PDF 文件' }}</span>
                <small>支持 .pdf 格式，单个文件不超过 20MB</small>
              </div>
              <input ref="fileInput" type="file" accept="application/pdf" class="file-input" @change="handleFileSelect" />
            </div>

            <div v-if="newAttachmentTitle" class="attachment-form">
              <h4>添加结构化来源</h4>
              <div class="form-row">
                <input v-model="newAttachmentTitle" type="text" placeholder="标题" />
                <input v-model="newAttachmentUrl" type="text" placeholder="URL" />
              </div>
              <div class="form-row">
                <input v-model="newAttachmentPublisher" type="text" placeholder="发布者（可选）" />
                <input v-model="newAttachmentSnippet" type="text" placeholder="摘要（可选）" />
              </div>
              <div class="form-actions">
                <button type="button" @click="addStructuredSource">添加来源</button>
                <button type="button" class="secondary" @click="clearNewAttachment">取消</button>
              </div>
            </div>

            <div v-else class="add-source-btn">
              <button type="button" @click="showStructuredForm = true">
                <Plus :size="14" />添加结构化来源
              </button>
            </div>

            <div class="attachment-list">
              <h4>已上传资料</h4>
              <div v-if="detail.attachments?.length" class="source-list">
                <a v-for="source in detail.attachments" :key="source.id" :href="source.url" target="_blank" rel="noreferrer" class="source-card">
                  <span>{{ source.sourceRef }}</span>
                  <div><strong>{{ source.title }}</strong><small>{{ source.publisher || source.url }}</small><p>{{ source.snippet }}</p></div>
                  <ExternalLink :size="14" />
                </a>
              </div>
              <div v-else class="result-empty"><Upload :size="30" /><strong>暂无资料</strong><span>上传论文 PDF 或添加结构化来源，帮助 Agent 深入调研。</span></div>
            </div>
          </div>
        </section>

        <aside class="ai-rail" :class="{ collapsed: chainRailCollapsed }">
          <button v-if="chainRailCollapsed" class="rail-collapsed" type="button" title="展开 AI 对话" @click="chainRailCollapsed = false">
            <Bot :size="16" />
            <span>AI 对话</span>
          </button>
          <template v-else>
            <div class="rail-tabs" role="tablist" aria-label="产业链 AI 模式">
              <button :class="{ active: railMode === 'planning' }" type="button" @click="setRailMode('planning')">
                <BrainCircuit :size="14" />调研规划
              </button>
              <button :class="{ active: railMode === 'ask' }" type="button" @click="setRailMode('ask')">
                <Bot :size="14" />向导问答
              </button>
              <button class="rail-close" type="button" title="收起右栏" @click="chainRailCollapsed = true">
                <PanelRightClose :size="15" />
              </button>
            </div>

            <div class="rail-body">
              <DialogWorkbench
                v-if="railMode === 'planning'"
                class="rail-dialog"
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
                @switch-to-map="setRailMode('ask')"
              />
              <AiChatPanel v-else class="rail-chat" :context-node="chainContextBrief" />
            </div>
          </template>
        </aside>
      </section>
    </main>
  </div>
</template>

<script setup lang="ts">
import { computed, defineAsyncComponent, onMounted, onUnmounted, ref, watch } from 'vue'
import type { ComponentPublicInstance } from 'vue'
import { AlertTriangle, ArrowLeft, BookOpenCheck, Bot, BrainCircuit, ExternalLink, FileDown, FileText, LoaderCircle, MessagesSquare, Network, PanelRightClose, Plus, SearchCheck, Upload } from '@lucide/vue'
import AppHeader from '../../../app/components/AppHeader.vue'
import { renderMarkdown } from '../../ai/utils/markdown'
import {
  cancelResearchRun,
  fetchForumEvents,
  fetchResearchCard,
  resumeResearchRun,
  sendResearchMessage,
  startResearchRun,
  streamResearchEvents,
  updateResearchCard,
  uploadResearchAttachment,
} from '../api'
import type { ForumEventView, ForumSsePayload, ResearchCardDetail } from '../model/types'
import type { NodeBrief } from '../../graph/types'

const DialogWorkbench = defineAsyncComponent(() => import('../../graph/components/DialogWorkbench.vue'))
const AiChatPanel = defineAsyncComponent(() => import('../../ai/components/AiChatPanel.vue'))
const AgentForum = defineAsyncComponent(() => import('../components/AgentForum.vue'))
const ResearchGraph = defineAsyncComponent(() => import('../components/ResearchGraph.vue'))
const RichReport = defineAsyncComponent(() => import('../components/RichReport.vue'))

const props = defineProps<{ id: number }>()
type RailMode = 'planning' | 'ask'
const detail = ref<ResearchCardDetail | null>(null)
const loading = ref(true)
const sending = ref(false)
const starting = ref(false)
const error = ref('')
const tab = ref<'graph' | 'report' | 'forum' | 'sources' | 'attachments'>('graph')
const railMode = ref<RailMode>('ask')
const railModeTouched = ref(false)
const chainRailCollapsed = ref(localStorage.getItem('industry_chain_ai_rail_collapsed') === '1')
const forumEvents = ref<ForumEventView[]>([])
const richReportRef = ref<ComponentPublicInstance | null>(null)
const exporting = ref(false)
let streamController: AbortController | null = null
let pollTimer: number | null = null

const fileInput = ref<HTMLInputElement | null>(null)
const uploading = ref(false)
const showStructuredForm = ref(false)
const newAttachmentTitle = ref('')
const newAttachmentUrl = ref('')
const newAttachmentPublisher = ref('')
const newAttachmentSnippet = ref('')

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
  finalizing: '保存调研结果',
}[detail.value?.card.currentStage || ''] || '准备调研'))
const chainContextBrief = computed<NodeBrief | null>(() => {
  const card = detail.value?.card
  if (!card) return null
  return {
    id: -card.id,
    code: `chain-${card.id}`,
    name: card.title,
    era: '产业链',
    eraRank: 0,
    yearLabel: card.status,
    summary: card.brief || '围绕当前产业链调研产物继续追问。',
    premium: false,
    category: '产业链',
    importance: card.nodeCount,
  }
})

function setRailMode(mode: RailMode) {
  railModeTouched.value = true
  railMode.value = mode
}

function syncDefaultRailMode() {
  if (railModeTouched.value || !detail.value) return
  railMode.value = detail.value.card.status === 'COMPLETED' ? 'ask' : 'planning'
}

async function load(silent = false) {
  if (!silent) loading.value = true
  try {
    detail.value = await fetchResearchCard(props.id)
    syncDefaultRailMode()
    error.value = detail.value.card.status === 'FAILED'
      ? (detail.value.card.lastError || '调研任务未完成，本轮过程记录已保留，可重新启动。')
      : ''
    // 还原历史论坛流(工作台初次进入 / 刷新)
    void loadForumHistory()
    if (researching.value) { tab.value = 'forum'; connectEvents() }
  } catch (e: any) {
    error.value = e.message || '调研工作台加载失败'
  } finally {
    loading.value = false
  }
}

async function loadForumHistory() {
  try {
    const history = await fetchForumEvents(props.id)
    forumEvents.value = history
  } catch {
    // 论坛历史为体验增强，失败静默
  }
}

function triggerFileInput() {
  fileInput.value?.click()
}

function handleDrop(e: DragEvent) {
  const files = e.dataTransfer?.files
  if (files?.length && files[0].type === 'application/pdf') {
    handleFile(files[0])
  }
}

function handleFileSelect(e: Event) {
  const input = e.target as HTMLInputElement
  if (input.files?.length) {
    handleFile(input.files[0])
  }
}

async function handleFile(file: File) {
  if (file.type !== 'application/pdf') {
    error.value = '请上传 PDF 格式文件'
    return
  }
  uploading.value = true
  error.value = ''
  try {
    await uploadResearchAttachment(props.id, file)
    await load(true)
  } catch (e: any) {
    error.value = e.message || '文件上传失败'
  } finally {
    uploading.value = false
    if (fileInput.value) fileInput.value.value = ''
  }
}

async function addStructuredSource() {
  if (!newAttachmentTitle.value || !newAttachmentUrl.value) {
    error.value = '请填写标题和 URL'
    return
  }
  error.value = ''
  try {
    const existing = detail.value?.attachments ?? []
    const newSources = [...existing.map(a => ({ title: a.title, url: a.url, publisher: a.publisher, snippet: a.snippet })), {
      title: newAttachmentTitle.value,
      url: newAttachmentUrl.value,
      publisher: newAttachmentPublisher.value || null,
      snippet: newAttachmentSnippet.value || null,
    }]
    await updateResearchCard(props.id, detail.value!.card.title, detail.value!.card.brief || '', newSources)
    await load(true)
    clearNewAttachment()
  } catch (e: any) {
    error.value = e.message || '添加来源失败'
  }
}

function clearNewAttachment() {
  showStructuredForm.value = false
  newAttachmentTitle.value = ''
  newAttachmentUrl.value = ''
  newAttachmentPublisher.value = ''
  newAttachmentSnippet.value = ''
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
    forumEvents.value = []
    if (detail.value) {
      detail.value.card.status = 'RESEARCHING'
      detail.value.card.currentStage = 'planning'
      detail.value.card.progress = 3
      detail.value.activeRun = { id: result.runId, status: 'RUNNING', currentStage: 'planning', progress: 3, errorMessage: null, startedAt: new Date().toISOString(), finishedAt: null }
    }
    railMode.value = 'planning'
    tab.value = 'forum'
    connectEvents()
  } catch (e: any) {
    error.value = e.message || '启动调研失败'
  } finally {
    starting.value = false
  }
}

function runPrimaryAction() {
  if (detail.value?.card.status === 'FAILED') void resumeRun()
  else void startRun()
}

async function resumeRun() {
  if (starting.value || !detail.value) return
  starting.value = true
  error.value = ''
  try {
    const result = await resumeResearchRun(props.id)
    const stage = result.currentStage || detail.value.card.currentStage || 'planning'
    detail.value.card.status = 'RESEARCHING'
    detail.value.card.currentStage = stage
    detail.value.card.progress = result.progress
    detail.value.card.lastError = null
    detail.value.activeRun = {
      id: result.runId,
      status: 'RUNNING',
      currentStage: stage,
      progress: result.progress,
      errorMessage: null,
      startedAt: new Date().toISOString(),
      finishedAt: null,
    }
    railMode.value = 'planning'
    tab.value = 'forum'
    connectEvents()
  } catch (e: any) {
    error.value = e.message || '断点续跑失败'
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
    if (event === 'forum') {
      appendForumEvent(data as unknown as ForumSsePayload)
      return
    }
    applyProgress(data)
    if (event === 'completed' || event === 'failed') {
      stopEvents()
      await load(true)
      if (event === 'completed') tab.value = 'report'
    }
  }, controller.signal).then(() => {
    if (!controller.signal.aborted && researching.value) startPolling()
  }).catch(() => {
    if (!controller.signal.aborted && researching.value) startPolling()
  }).finally(() => {
    if (streamController === controller) streamController = null
  })
}

function appendForumEvent(payload: ForumSsePayload) {
  const event = payload.event
  forumEvents.value.push({
    id: Date.now() + Math.random(),
    source: event.source,
    sourceText: ({
      INDUSTRY: '行业 Agent',
      QUERY: '检索 Agent',
      INSIGHT: '洞察 Agent',
      HOST: '论坛主持人',
      SYSTEM: '系统',
    } as Record<string, string>)[event.source] || event.source,
    content: event.content,
    createdAt: event.createdAt,
  })
}

/** 点击报告里的来源徽章 [Sx] → 跳到来源 Tab 并高亮对应条目。 */
function jumpToSource(_ref: string) {
  tab.value = 'sources'
}

/** 前端导出 PDF：用 html2canvas 截取 RichReport DOM + jsPDF 合成(无需服务端依赖)。 */
async function exportReportPdf() {
  if (exporting.value) return
  const el = richReportRef.value?.$el as HTMLElement | undefined
  if (!el) return
  exporting.value = true
  try {
    const [{ default: html2canvas }, { jsPDF }] = await Promise.all([import('html2canvas'), import('jspdf')])
    const canvas = await html2canvas(el, { scale: 2, backgroundColor: '#ffffff', useCORS: true })
    const img = canvas.toDataURL('image/jpeg', 0.92)
    const pdf = new jsPDF({ orientation: 'p', unit: 'pt', format: 'a4' })
    const pageW = pdf.internal.pageSize.getWidth()
    const pageH = pdf.internal.pageSize.getHeight()
    const imgH = (canvas.height * pageW) / canvas.width
    let position = 0
    let remaining = imgH
    // 分页：A4 宽度铺满，超出高度自动续页
    pdf.addImage(img, 'JPEG', 0, position, pageW, imgH)
    remaining -= pageH
    while (remaining > 0) {
      position -= pageH
      pdf.addPage()
      pdf.addImage(img, 'JPEG', 0, position, pageW, imgH)
      remaining -= pageH
    }
    pdf.save(`${detail.value?.card.title || '产业链深度报告'}.pdf`)
  } catch (e: any) {
    error.value = '导出 PDF 失败：' + (e?.message || '未知错误')
  } finally {
    exporting.value = false
  }
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

watch(chainRailCollapsed, value => localStorage.setItem('industry_chain_ai_rail_collapsed', value ? '1' : '0'))
watch(() => props.id, () => {
  railModeTouched.value = false
  forumEvents.value = []
  stopEvents()
  void load()
})
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
.ai-rail { flex: 0 0 360px; min-width: 0; display: flex; flex-direction: column; border: 1px solid var(--line); border-radius: 8px; background: #fff; overflow: hidden; transition: flex-basis .3s cubic-bezier(.25,.8,.25,1), background .2s ease, border-color .2s ease; }
/* 折叠态：容器透明融入背景，去掉白底圆角防露白；低调浅色不抢眼 */
.ai-rail.collapsed { flex: 0 0 44px; background: transparent; border-color: transparent; }
.rail-collapsed { width: 100%; height: 100%; display: flex; flex-direction: column; align-items: center; gap: 10px; padding: 14px 0; border: 0; background: transparent; color: var(--muted); cursor: pointer; transition: color .16s ease, background .16s ease; }
.rail-collapsed:hover { background: var(--surface); color: var(--accent); }
.rail-collapsed span { writing-mode: vertical-rl; letter-spacing: .12em; font-size: 12px; font-weight: 800; }
.rail-tabs { flex: none; display: grid; grid-template-columns: 1fr 1fr auto; min-height: 46px; border-bottom: 1px solid var(--line); background: var(--surface); }
.rail-tabs button { display: inline-flex; align-items: center; justify-content: center; gap: 6px; border: 0; border-right: 1px solid var(--line); background: transparent; color: var(--muted); font-size: 12px; font-weight: 800; cursor: pointer; }
.rail-tabs button.active { background: #fff; color: var(--ink); box-shadow: inset 0 -2px 0 var(--accent); }
.rail-tabs svg { color: currentColor; }
.rail-tabs .rail-close { width: 42px; border-right: 0; color: var(--ink-2); }
.rail-tabs .rail-close:hover { color: var(--accent); }
.rail-body { flex: 1; min-height: 0; display: flex; }
.rail-chat { flex: 1; min-width: 0; }
.rail-body :deep(.conversation-workbench) { flex: 1 1 auto; width: 100%; min-width: 0; min-height: 0; border: 0; border-radius: 0; box-shadow: none; }
.result-tabs { flex: none; display: flex; min-height: 56px; align-items: stretch; padding: 0 12px; border-bottom: 1px solid var(--line); }
.result-tabs button { display: inline-flex; align-items: center; gap: 7px; border: 0; border-bottom: 2px solid transparent; background: transparent; padding: 0 14px; color: var(--muted); font-size: 12px; font-weight: 800; cursor: pointer; }
.result-tabs button.active { border-bottom-color: var(--accent); color: var(--ink); }
.result-tabs span { padding: 2px 6px; border-radius: 99px; background: var(--surface); font-size: 9px; }
.run-progress { flex: none; height: 3px; background: #f0f1f2; }
.run-progress i { display: block; height: 100%; background: var(--accent); transition: width .3s ease; }
.report-view, .source-list { flex: 1; min-height: 0; overflow-y: auto; padding: 24px; }
.report-toolbar { max-width: 840px; margin: 0 auto 14px; display: flex; justify-content: flex-end; }
.export-btn { display: inline-flex; align-items: center; gap: 6px; min-height: 32px; padding: 0 12px; border: 1px solid var(--accent); border-radius: 6px; background: var(--accent); color: #fff; font-size: 12px; cursor: pointer; }
.export-btn:disabled { opacity: .55; cursor: default; }
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

.attachment-panel { flex: 1; min-height: 0; overflow-y: auto; padding: 24px; }
.attachment-upload { margin-bottom: 20px; }
.upload-area { display: grid; place-content: center; justify-items: center; gap: 8px; min-height: 100px; border: 2px dashed var(--line); border-radius: 8px; cursor: pointer; color: var(--muted); }
.upload-area:hover { border-color: var(--accent); color: var(--ink); }
.upload-area span { font-size: 13px; }
.upload-area small { font-size: 11px; }
.file-input { display: none; }
.attachment-form { margin-bottom: 20px; padding: 16px; border: 1px solid var(--line); border-radius: 8px; }
.attachment-form h4 { margin: 0 0 12px; font-size: 13px; }
.form-row { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; margin-bottom: 10px; }
.form-row input { width: 100%; padding: 8px 10px; border: 1px solid var(--line); border-radius: 6px; font-size: 12px; }
.form-actions { display: flex; gap: 10px; margin-top: 12px; }
.form-actions button { padding: 8px 14px; border: 1px solid var(--line); border-radius: 6px; background: var(--accent); color: #fff; font-size: 12px; cursor: pointer; }
.form-actions .secondary { background: #fff; color: var(--ink); }
.add-source-btn { margin-bottom: 20px; }
.add-source-btn button { display: inline-flex; align-items: center; gap: 6px; padding: 8px 14px; border: 1px solid var(--line); border-radius: 6px; background: #fff; color: var(--ink); font-size: 12px; cursor: pointer; }
.add-source-btn button:hover { border-color: var(--accent); color: var(--accent); }
.attachment-list h4 { margin: 0 0 12px; font-size: 13px; }

@media (max-width: 920px) {
  .research-page { height: calc(100vh - 48px); overflow-y: auto; }
  .research-header { align-items: flex-start; flex-direction: column; }
  .workbench-layout { flex-direction: column; overflow: visible; }
  .result-panel { min-height: 600px; }
  .ai-rail { flex: 0 0 auto; min-height: 540px; }
  .ai-rail.collapsed { min-height: 44px; flex-basis: auto; }
  .rail-collapsed { min-height: 44px; flex-direction: row; justify-content: center; padding: 0 14px; }
  .rail-collapsed span { writing-mode: horizontal-tb; letter-spacing: .08em; }
}
</style>
