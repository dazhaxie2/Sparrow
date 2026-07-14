<template>
  <div class="admin-shell">
    <main class="admin-page">
      <div class="page-actions">
        <button class="btn primary" type="button" @click="openCreate()"><Plus :size="15" />新建配置</button>
      </div>

      <section v-if="loading" class="state-box"><LoaderCircle class="spin" :size="20" /><span>加载配置…</span></section>
      <section v-else-if="loadError" class="state-box error">
        <AlertTriangle :size="20" /><span>{{ loadError }}</span>
        <button type="button" @click="loadAll">重试</button>
      </section>
      <!-- 按固定场景分组的模型池 -->
      <section v-else class="model-pool">
        <section v-for="group in configGroups" :key="group.scene.value" class="scene-group">
          <header class="scene-head">
            <div>
              <h2>{{ group.scene.label }}</h2>
              <span>{{ kindLabel(group.scene.kind) }} · {{ group.configs.length }} 个配置</span>
            </div>
            <button class="btn ghost" type="button" @click="openCreate(group.scene.value)">
              <Plus :size="14" />添加
            </button>
          </header>
          <div v-if="group.configs.length" class="config-grid">
            <article v-for="c in group.configs" :key="c.id" class="config-card" :class="{ active: c.active }">
              <div class="card-head">
                <div>
                  <span class="badge" v-if="c.active">当前激活</span>
                  <h3>{{ c.name }}</h3>
                </div>
                <span class="card-id">#{{ c.id }}</span>
              </div>
              <dl class="card-fields">
                <div><dt>类型</dt><dd>{{ kindLabel(c.modelKind) }}</dd></div>
                <div><dt>Base URL</dt><dd>{{ c.baseUrl }}</dd></div>
                <div><dt>模型</dt><dd>{{ c.modelName }}</dd></div>
                <div><dt>API Key</dt><dd class="mono">{{ c.apiKeyMasked || '(未设置)' }}</dd></div>
                <div><dt>超时 / 重试<span v-if="c.modelKind === 'chat'"> / maxTokens</span></dt>
                  <dd>{{ c.timeoutSeconds }}s / {{ c.maxRetries }}<span v-if="c.modelKind === 'chat'"> / {{ c.maxTokens }}</span></dd>
                </div>
              </dl>
              <div class="card-actions">
                <button class="btn" type="button" @click="openEdit(c)"><Pencil :size="14" />编辑</button>
                <button v-if="!c.active" class="btn accent" type="button" @click="handleActivate(c)"><Power :size="14" />激活</button>
                <span v-else class="active-tag">已激活</span>
              </div>
            </article>
          </div>
          <p v-else class="empty-scene">该场景尚无配置。</p>
        </section>
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
      <div v-if="editorOpen" class="sparrow-overlay" @mousedown="dismiss.onMaskMousedown" @mouseup="dismiss.onMaskMouseup">
        <form class="sparrow-modal editor-box" @submit.prevent="handleSave">
          <div class="editor-head">
            <h3>{{ editing?.id ? '编辑配置' : '新建配置' }}</h3>
            <button type="button" class="icon-btn" @click="closeEditor">×</button>
          </div>

          <label class="field"><span>名称</span>
            <input v-model="editing.name" type="text" maxlength="64" placeholder="如 GLM-4.5 生产" />
          </label>
          <div class="field-row two">
            <label class="field"><span>场景</span>
              <select v-model="editing.scene" :disabled="editing.active" @change="syncKindFromScene">
                <option v-for="scene in MODEL_SCENES" :key="scene.value" :value="scene.value">{{ scene.label }}</option>
              </select>
              <em v-if="editing.active" class="hint">激活配置不能移动场景</em>
            </label>
            <label class="field"><span>模型类型</span>
              <select v-model="editing.modelKind" disabled>
                <option v-for="kind in MODEL_KINDS" :key="kind.value" :value="kind.value">{{ kind.label }}</option>
              </select>
            </label>
          </div>
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
            <label v-if="editing.modelKind === 'chat'" class="field"><span>maxTokens</span>
              <input v-model.number="editing.maxTokens" type="number" min="100" max="100000" />
            </label>
            <label class="field"><span>超时(秒)</span>
              <input v-model.number="editing.timeoutSeconds" type="number" min="5" max="600" />
            </label>
            <label class="field"><span>重试次数</span>
              <input v-model.number="editing.maxRetries" type="number" min="0" max="10" />
            </label>
          </div>

          <p v-if="editorError" class="form-error">{{ editorError }}</p>
          <p v-if="editorInfo" class="form-info">{{ editorInfo }}</p>

          <div class="editor-actions">
            <button v-if="!testing" class="btn" type="button" :disabled="saving" @click="handleTest">
              <Plug :size="14" />测试连接
            </button>
            <button v-else class="btn danger" type="button" @click="cancelTest">
              <X :size="14" />取消测试
            </button>
            <button class="btn primary" type="submit" :disabled="saving || testing">{{ saving ? '保存中…' : '保存' }}</button>
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
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { AlertTriangle, LoaderCircle, Pencil, Plug, Plus, Power, X } from '@lucide/vue'
import { useToast } from '../../../shared/composables/useCommon'
import { useDismissableOverlay } from '../../../shared/composables/useDismissableOverlay'
import {
  activateModelConfig,
  listAudits,
  listModelConfigs,
  saveModelConfig,
  testModelConfig,
} from '../api'
import type { ModelConfig, ModelConfigAudit, SaveConfigPayload, TestConfigPayload } from '../types'
import { MODEL_CONFIG_ACTION, MODEL_SCENES, MODEL_KINDS, kindForScene, kindLabel } from '../types'

const configs = ref<ModelConfig[]>([])
const audits = ref<ModelConfigAudit[]>([])
const loading = ref(true)
const loadError = ref('')
const auditsLoading = ref(false)
const configGroups = computed(() => MODEL_SCENES.map(scene => ({
  scene,
  configs: configs.value.filter(config => config.scene === scene.value),
})))

// 编辑器
const editorOpen = ref(false)
const editing = reactive<EditState>(blankEdit())
const testing = ref(false)
const saving = ref(false)
const editorError = ref('')
const editorInfo = ref('')
const MODEL_TEST_TIMEOUT_SECONDS = 15
const MODEL_TEST_CLIENT_TIMEOUT_MS = 18_000
let testController: AbortController | null = null
let testTimeoutHandle: number | null = null

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
  scene: string
  modelKind: string
  active: boolean
}

function blankEdit(): EditState {
  return { id: null, name: '', baseUrl: '', modelName: '', apiKey: '', apiKeyMasked: '',
    maxTokens: 3000, timeoutSeconds: 180, maxRetries: 2,
    scene: 'chain_planning', modelKind: 'chat', active: false }
}

onMounted(loadAll)
onBeforeUnmount(() => stopTest('cancelled', false))

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

function openCreate(scene = 'chain_planning') {
  Object.assign(editing, blankEdit())
  editing.scene = scene
  syncKindFromScene()
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
  editing.scene = c.scene
  editing.modelKind = c.modelKind
  editing.active = c.active
  editorError.value = ''
  editorInfo.value = ''
  editorOpen.value = true
}

function syncKindFromScene() {
  editing.modelKind = kindForScene(editing.scene)
  if (editing.modelKind === 'embedding') editing.maxTokens = 0
  else if (editing.maxTokens < 100) editing.maxTokens = 3000
}

function closeEditor() {
  stopTest('cancelled', false)
  editorOpen.value = false
}

const dismiss = useDismissableOverlay(closeEditor)

async function handleTest() {
  editorError.value = ''
  editorInfo.value = ''
  if (!editing.baseUrl || !editing.modelName) {
    editorError.value = 'Base URL 与模型名称不能为空'
    return
  }
  const controller = new AbortController()
  testController = controller
  testing.value = true
  editorInfo.value = `正在测试连接，最多等待 ${MODEL_TEST_TIMEOUT_SECONDS} 秒，可随时取消。`
  testTimeoutHandle = window.setTimeout(() => {
    if (testController === controller) stopTest('timeout')
  }, MODEL_TEST_CLIENT_TIMEOUT_MS)
  try {
    const payload: TestConfigPayload = {
      name: editing.name,
      baseUrl: editing.baseUrl,
      modelName: editing.modelName,
      apiKey: editing.apiKey,
      timeoutSeconds: MODEL_TEST_TIMEOUT_SECONDS,
      scene: editing.scene,
      modelKind: editing.modelKind,
    }
    const result = await testModelConfig(payload, controller.signal)
    if (testController !== controller) return
    if (result.ok) {
      editorInfo.value = '✓ ' + result.message
      showToast('测试连接成功')
    } else {
      editorError.value = '✗ ' + result.message
    }
    clearTestTimeout()
    testController = null
    testing.value = false
    await loadAudits()
  } catch (e: any) {
    if (controller.signal.aborted) return
    editorError.value = e.message
  } finally {
    if (testController === controller) {
      clearTestTimeout()
      testController = null
      testing.value = false
    }
  }
}

function cancelTest() {
  stopTest('cancelled')
}

function stopTest(reason: 'cancelled' | 'timeout', showFeedback = true) {
  if (!testController) return
  testController.abort()
  testController = null
  clearTestTimeout()
  testing.value = false
  if (!showFeedback) return
  editorError.value = ''
  editorInfo.value = reason === 'timeout'
    ? '测试等待超时，已停止等待。请检查模型地址、网络或服务状态。'
    : '已取消连接测试。'
}

function clearTestTimeout() {
  if (testTimeoutHandle === null) return
  window.clearTimeout(testTimeoutHandle)
  testTimeoutHandle = null
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
      scene: editing.scene,
      modelKind: editing.modelKind,
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
  const delayed = c.scene.startsWith('sparrow_ai_')
  const effect = delayed
    ? '配置将成为权威激活项，并在 sparrow-ai 下次启动时装配。'
    : '新请求将立即使用此配置，进行中的调研不受影响。'
  if (!confirm(`确定激活「${c.name}」吗？${effect}`)) return
  try {
    await activateModelConfig(c.id)
    showToast(delayed ? `已激活：${c.name}（重启 sparrow-ai 后生效）` : `已激活：${c.name}`)
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

.page-actions {
  display: flex;
  justify-content: flex-end;
  margin-bottom: 18px;
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
.btn.danger { border-color: var(--danger); color: var(--danger); background: rgba(220, 38, 38, 0.06); }
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

.model-pool { display: grid; gap: 24px; }

.scene-group { display: grid; gap: 10px; }

.scene-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  border-bottom: 1px solid var(--line-strong);
  padding-bottom: 8px;
}

.scene-head h2 { font-size: 17px; }
.scene-head span { color: var(--muted); font-size: 11px; }
.empty-scene { border: 1px dashed var(--line); color: var(--muted); padding: 14px; font-size: 12px; }

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
.editor-box {
  width: min(520px, calc(100vw - 32px));
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

.field input,
.field select {
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
.field select:focus { border-color: var(--ink); background: var(--panel); }
.field select:disabled { color: var(--ink-2); cursor: not-allowed; }

.field-row {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 10px;
}

.field-row.two { grid-template-columns: repeat(2, 1fr); }

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
