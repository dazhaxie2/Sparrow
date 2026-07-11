<template>
  <div class="admin-shell">
    <main class="admin-page">
      <header class="page-header">
        <div>
          <span class="eyebrow">ADMIN · MODEL CONFIG</span>
          <h1>模型配置</h1>
        </div>
        <button class="btn primary" type="button" @click="openCreate"><Plus :size="15" />新建配置</button>
      </header>
      <p class="page-desc">
        管理产业链调研使用的 LLM。保存后点击「测试连接」,通过后「激活」即可热切换,
        新请求立即生效,进行中的调研继续用旧模型。API Key 以 AES-GCM 加密存储,仅显示脱敏值。
      </p>

      <section v-if="loading" class="state-box"><LoaderCircle class="spin" :size="20" /><span>加载配置…</span></section>
      <section v-else-if="loadError" class="state-box error">
        <AlertTriangle :size="20" /><span>{{ loadError }}</span>
        <button type="button" @click="loadAll">重试</button>
      </section>
      <section v-else-if="!configs.length" class="empty-box">
        <span>暂无模型配置,点击「新建配置」创建第一个。</span>
      </section>

      <!-- 配置列表 -->
      <section v-else class="config-grid">
        <article v-for="c in configs" :key="c.id" class="config-card" :class="{ active: c.active }">
          <div class="card-head">
            <div>
              <span class="badge" v-if="c.active">当前激活</span>
              <h3>{{ c.name }}</h3>
            </div>
            <span class="card-id">#{{ c.id }}</span>
          </div>
          <dl class="card-fields">
            <div><dt>Base URL</dt><dd>{{ c.baseUrl }}</dd></div>
            <div><dt>模型</dt><dd>{{ c.modelName }}</dd></div>
            <div><dt>API Key</dt><dd class="mono">{{ c.apiKeyMasked || '(未设置)' }}</dd></div>
            <div><dt>超时 / 重试 / maxTokens</dt><dd>{{ c.timeoutSeconds }}s / {{ c.maxRetries }} / {{ c.maxTokens }}</dd></div>
          </dl>
          <div class="card-actions">
            <button class="btn" type="button" @click="openEdit(c)"><Pencil :size="14" />编辑</button>
            <button v-if="!c.active" class="btn accent" type="button" @click="handleActivate(c)"><Power :size="14" />激活</button>
            <span v-else class="active-tag">已激活</span>
          </div>
        </article>
      </section>

      <!-- 审计 -->
      <section class="audit-section">
        <div class="section-heading"><h2>操作审计</h2><button class="btn ghost" type="button" @click="loadAudits">刷新</button></div>
        <div v-if="auditsLoading" class="state-box small"><LoaderCircle class="spin" :size="16" /><span>加载…</span></div>
        <ol v-else-if="audits.length" class="audit-list">
          <li v-for="a in audits" :key="a.id">
            <span class="audit-action" :class="actionClass(a.action)">{{ a.action }}</span>
            <span class="audit-summary">{{ a.summary }}</span>
            <span class="audit-meta">#{{ a.operatorId }} · {{ formatTime(a.createdAt) }}</span>
          </li>
        </ol>
        <p v-else class="empty-mini">暂无审计记录</p>
      </section>

      <!-- 编辑/新建 弹层 -->
      <div v-if="editorOpen" class="editor-overlay" @click.self="closeEditor">
        <form class="editor-box" @submit.prevent="handleSave">
          <div class="editor-head">
            <h3>{{ editing?.id ? '编辑配置' : '新建配置' }}</h3>
            <button type="button" class="icon-btn" @click="closeEditor">×</button>
          </div>

          <label class="field"><span>名称</span>
            <input v-model="editing.name" type="text" maxlength="64" placeholder="如 GLM-4.5 生产" />
          </label>
          <label class="field"><span>Base URL</span>
            <input v-model="editing.baseUrl" type="text" placeholder="https://open.bigmodel.cn/api/paas/v4" />
          </label>
          <label class="field"><span>模型名称</span>
            <input v-model="editing.modelName" type="text" placeholder="glm-4.5-air" />
          </label>
          <label class="field">
            <span>API Key <em v-if="editing.id" class="hint">留空保留旧值 ({{ editing.apiKeyMasked }})</em></span>
            <input v-model="editing.apiKey" type="password" autocomplete="off" :placeholder="editing.id ? '留空保留旧 Key' : 'sk-...'" />
          </label>
          <div class="field-row">
            <label class="field"><span>maxTokens</span>
              <input v-model.number="editing.maxTokens" type="number" min="1" max="32000" />
            </label>
            <label class="field"><span>超时(秒)</span>
              <input v-model.number="editing.timeoutSeconds" type="number" min="1" max="600" />
            </label>
            <label class="field"><span>重试次数</span>
              <input v-model.number="editing.maxRetries" type="number" min="0" max="5" />
            </label>
          </div>

          <p v-if="editorError" class="form-error">{{ editorError }}</p>
          <p v-if="editorInfo" class="form-info">{{ editorInfo }}</p>

          <div class="editor-actions">
            <button class="btn" type="button" :disabled="testing" @click="handleTest">
              <LoaderCircle v-if="testing" class="spin" :size="14" />
              <Plug v-else :size="14" />测试连接
            </button>
            <button class="btn primary" type="submit" :disabled="saving">{{ saving ? '保存中…' : '保存' }}</button>
            <button class="btn ghost" type="button" @click="closeEditor">取消</button>
          </div>
        </form>
      </div>

      <!-- 全局 toast -->
      <transition name="fade">
        <div v-if="toast.visible" class="toast" :class="toast.kind">{{ toast.text }}</div>
      </transition>
    </main>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { AlertTriangle, LoaderCircle, Pencil, Plug, Plus, Power } from '@lucide/vue'
import { useToast } from '../../../shared/composables/useCommon'
import {
  activateModelConfig,
  listAudits,
  listModelConfigs,
  saveModelConfig,
  testModelConfig,
} from '../api'
import type { ModelConfig, ModelConfigAudit, SaveConfigPayload, TestConfigPayload } from '../types'
import { MODEL_CONFIG_ACTION } from '../types'

const configs = ref<ModelConfig[]>([])
const audits = ref<ModelConfigAudit[]>([])
const loading = ref(true)
const loadError = ref('')
const auditsLoading = ref(false)

// 编辑器
const editorOpen = ref(false)
const editing = reactive<EditState>(blankEdit())
const testing = ref(false)
const saving = ref(false)
const editorError = ref('')
const editorInfo = ref('')

// toast(复用公共 useToast)
const toast = useToast()
const showToast = (text: string, kind: 'ok' | 'err' = 'ok') => toast.show(text, kind, 3000)

interface EditState {
  id: number | null
  name: string
  baseUrl: string
  modelName: string
  apiKey: string
  apiKeyMasked: string
  maxTokens: number
  timeoutSeconds: number
  maxRetries: number
}

function blankEdit(): EditState {
  return { id: null, name: '', baseUrl: '', modelName: '', apiKey: '', apiKeyMasked: '',
    maxTokens: 3000, timeoutSeconds: 180, maxRetries: 2 }
}

onMounted(loadAll)

async function loadAll() {
  loading.value = true
  loadError.value = ''
  try {
    await loadConfigs()
    await loadAudits()
  } catch (e: any) {
    loadError.value = e.message
  } finally {
    loading.value = false
  }
}

async function loadConfigs() {
  configs.value = await listModelConfigs()
}

async function loadAudits() {
  auditsLoading.value = true
  try {
    audits.value = await listAudits(50)
  } catch (e: any) {
    showToast('加载审计失败: ' + e.message, 'err')
  } finally {
    auditsLoading.value = false
  }
}

function openCreate() {
  Object.assign(editing, blankEdit())
  editorError.value = ''
  editorInfo.value = ''
  editorOpen.value = true
}

function openEdit(c: ModelConfig) {
  editing.id = c.id
  editing.name = c.name
  editing.baseUrl = c.baseUrl
  editing.modelName = c.modelName
  editing.apiKey = ''
  editing.apiKeyMasked = c.apiKeyMasked
  editing.maxTokens = c.maxTokens
  editing.timeoutSeconds = c.timeoutSeconds
  editing.maxRetries = c.maxRetries
  editorError.value = ''
  editorInfo.value = ''
  editorOpen.value = true
}

function closeEditor() {
  editorOpen.value = false
}

async function handleTest() {
  editorError.value = ''
  editorInfo.value = ''
  if (!editing.baseUrl || !editing.modelName) {
    editorError.value = 'Base URL 与模型名称不能为空'
    return
  }
  testing.value = true
  try {
    const payload: TestConfigPayload = {
      name: editing.name,
      baseUrl: editing.baseUrl,
      modelName: editing.modelName,
      apiKey: editing.apiKey,
      timeoutSeconds: editing.timeoutSeconds,
    }
    const result = await testModelConfig(payload)
    if (result.ok) {
      editorInfo.value = '✓ ' + result.message
      showToast('测试连接成功')
    } else {
      editorError.value = '✗ ' + result.message
    }
    await loadAudits()
  } catch (e: any) {
    editorError.value = e.message
  } finally {
    testing.value = false
  }
}

async function handleSave() {
  editorError.value = ''
  editorInfo.value = ''
  if (!editing.name || !editing.baseUrl || !editing.modelName) {
    editorError.value = '名称、Base URL、模型名称不能为空'
    return
  }
  if (!editing.id && !editing.apiKey) {
    editorError.value = '新建配置必须填写 API Key'
    return
  }
  saving.value = true
  try {
    const payload: SaveConfigPayload = {
      id: editing.id,
      name: editing.name,
      baseUrl: editing.baseUrl,
      modelName: editing.modelName,
      apiKey: editing.apiKey,
      maxTokens: editing.maxTokens,
      timeoutSeconds: editing.timeoutSeconds,
      maxRetries: editing.maxRetries,
    }
    await saveModelConfig(payload)
    showToast('已保存')
    editorOpen.value = false
    await loadConfigs()
  } catch (e: any) {
    editorError.value = e.message
  } finally {
    saving.value = false
  }
}

async function handleActivate(c: ModelConfig) {
  if (!c.id) return
  if (!confirm(`确定激活「${c.name}」吗?新请求将立即使用此配置,进行中的调研不受影响。`)) return
  try {
    await activateModelConfig(c.id)
    showToast(`已激活: ${c.name}`)
    await loadConfigs()
    await loadAudits()
  } catch (e: any) {
    showToast('激活失败: ' + e.message, 'err')
  }
}

function actionClass(action: string) {
  if (action === MODEL_CONFIG_ACTION.ACTIVATE) return 'act-activate'
  if (action === MODEL_CONFIG_ACTION.TEST) return 'act-test'
  return 'act-save'
}

function formatTime(iso: string) {
  try {
    return new Date(iso).toLocaleString('zh-CN', { hour12: false, timeZone: 'Asia/Shanghai' })
  } catch {
    return iso
  }
}
</script>

<style scoped>
.admin-shell {
  min-height: 100%;
  background: var(--bg);
}

.admin-page {
  max-width: 980px;
  margin: 0 auto;
  padding: 28px 24px 60px;
}

.page-header {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 8px;
}

.eyebrow {
  color: var(--accent);
  font-size: 11px;
  font-weight: 800;
  letter-spacing: 0.14em;
}

.page-header h1 {
  margin-top: 6px;
  font-size: 28px;
}

.page-desc {
  color: var(--ink-2);
  font-size: 13px;
  line-height: 1.7;
  margin: 8px 0 28px;
  max-width: 720px;
}

.btn {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  min-height: 34px;
  border: 1px solid var(--line-strong);
  background: var(--panel);
  color: var(--ink);
  padding: 0 12px;
  font-size: 12px;
  cursor: pointer;
}

.btn:hover:not(:disabled) { border-color: var(--ink); }
.btn:disabled { opacity: 0.6; cursor: not-allowed; }

.btn.primary { border-color: var(--ink); background: var(--ink); color: var(--bg); font-weight: 800; }
.btn.accent { border-color: var(--accent); color: var(--accent); background: rgba(255, 87, 34, 0.08); }
.btn.ghost { background: transparent; }

.icon-btn {
  width: 28px; height: 28px;
  border: 1px solid var(--line-strong);
  background: var(--panel);
  color: var(--ink);
  cursor: pointer;
}

.state-box {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 24px;
  border: 1px solid var(--line);
  color: var(--ink-2);
  font-size: 13px;
}

.state-box.small { padding: 12px; }
.state-box.error { color: var(--danger); border-color: rgba(220, 38, 38, 0.3); }

.empty-box {
  padding: 40px;
  border: 1px dashed var(--line-strong);
  text-align: center;
  color: var(--ink-2);
  font-size: 13px;
}

.spin { animation: spin 1s linear infinite; }
@keyframes spin { to { transform: rotate(360deg); } }

.config-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(300px, 1fr));
  gap: 14px;
}

.config-card {
  border: 1px solid var(--line);
  background: var(--panel);
  padding: 16px;
}

.config-card.active {
  border-color: var(--accent);
  box-shadow: 0 0 0 1px var(--accent) inset;
}

.card-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 12px;
}

.card-head h3 { font-size: 16px; }

.badge {
  display: inline-block;
  background: var(--accent);
  color: #fff;
  font-size: 10px;
  font-weight: 800;
  letter-spacing: 0.08em;
  padding: 2px 6px;
  margin-bottom: 6px;
}

.card-id { color: var(--muted); font-size: 11px; }

.card-fields {
  display: grid;
  gap: 8px;
  margin: 0 0 14px;
}

.card-fields > div { display: grid; gap: 2px; }

.card-fields dt { color: var(--ink-2); font-size: 10px; font-weight: 700; letter-spacing: 0.06em; text-transform: uppercase; }

.card-fields dd { font-size: 13px; word-break: break-all; }

.mono { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; }

.card-actions {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}

.active-tag {
  color: var(--accent);
  font-size: 12px;
  font-weight: 700;
  align-self: center;
}

.audit-section { margin-top: 40px; }

.section-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
}

.section-heading h2 { font-size: 18px; }

.audit-list {
  list-style: none;
  margin: 0;
  padding: 0;
  border: 1px solid var(--line);
}

.audit-list li {
  display: grid;
  grid-template-columns: 90px 1fr auto;
  gap: 12px;
  align-items: center;
  padding: 10px 14px;
  border-bottom: 1px solid var(--line);
  font-size: 12px;
}

.audit-list li:last-child { border-bottom: 0; }

.audit-action {
  font-size: 10px;
  font-weight: 800;
  letter-spacing: 0.06em;
  padding: 3px 6px;
  text-align: center;
}

.act-activate { background: rgba(255, 87, 34, 0.12); color: var(--accent); }
.act-test { background: rgba(59, 130, 246, 0.12); color: #2563eb; }
.act-save { background: var(--surface); color: var(--ink-2); }

.audit-summary { color: var(--ink); word-break: break-all; }
.audit-meta { color: var(--muted); white-space: nowrap; }

.empty-mini { color: var(--muted); font-size: 12px; }

/* 编辑弹层 */
.editor-overlay {
  position: fixed;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  background: rgba(0, 0, 0, 0.42);
  backdrop-filter: blur(2px);
  z-index: 100;
}

.editor-box {
  width: min(520px, calc(100vw - 32px));
  max-height: 88vh;
  overflow-y: auto;
  border: 1px solid var(--ink);
  background: var(--panel);
  box-shadow: var(--shadow-md);
  padding: 22px;
}

.editor-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding-bottom: 14px;
  border-bottom: 1px solid var(--line);
  margin-bottom: 14px;
}

.editor-head h3 { font-size: 20px; }

.field { display: grid; gap: 6px; margin-top: 12px; }

.field span {
  color: var(--ink-2);
  font-size: 11px;
  font-weight: 800;
  letter-spacing: 0.06em;
}

.field .hint { color: var(--muted); font-weight: 400; font-style: normal; }

.field input {
  width: 100%;
  height: 38px;
  border: 1px solid var(--line-strong);
  background: var(--surface);
  color: var(--ink);
  padding: 0 11px;
  font-size: 13px;
  outline: none;
}

.field input:focus { border-color: var(--ink); background: var(--panel); }

.field-row {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 10px;
}

.form-error {
  margin-top: 12px;
  border: 1px solid rgba(220, 38, 38, 0.32);
  background: rgba(220, 38, 38, 0.06);
  color: var(--danger);
  padding: 9px 10px;
  font-size: 12px;
}

.form-info {
  margin-top: 12px;
  border: 1px solid var(--line);
  background: var(--surface);
  color: var(--ink-2);
  padding: 9px 10px;
  font-size: 12px;
}

.editor-actions {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
  margin-top: 18px;
}

/* toast */
.toast {
  position: fixed;
  bottom: 28px;
  left: 50%;
  transform: translateX(-50%);
  z-index: 300;
  padding: 10px 18px;
  font-size: 13px;
  border: 1px solid var(--line-strong);
  background: var(--panel);
  box-shadow: var(--shadow-md);
}

.toast.ok { border-color: var(--accent); color: var(--accent); }
.toast.err { border-color: var(--danger); color: var(--danger); }

.fade-enter-active, .fade-leave-active { transition: opacity 0.2s; }
.fade-enter-from, .fade-leave-to { opacity: 0; }

@media (max-width: 640px) {
  .field-row { grid-template-columns: 1fr; }
  .audit-list li { grid-template-columns: 1fr; gap: 4px; }
}
</style>
