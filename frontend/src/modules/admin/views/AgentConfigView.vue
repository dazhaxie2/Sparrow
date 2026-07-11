<template>
  <div class="admin-shell">
    <main class="admin-page">
      <header class="page-head">
        <div>
          <span class="eyebrow">ADMIN · AGENT CONTROL</span>
          <h1>Agent 配置中心</h1>
          <p>统一管理所有面向用户的 Agent。保存后，新请求会读取最新提示词与运行边界。</p>
        </div>
        <router-link class="model-link" to="/admin/models">模型与 API 配置</router-link>
      </header>

      <div class="toolbar">
        <button
          v-for="option in filters"
          :key="option.value"
          type="button"
          :class="{ active: filter === option.value }"
          @click="filter = option.value"
        >{{ option.label }}</button>
        <button class="refresh" type="button" :disabled="loading" @click="load">刷新</button>
      </div>

      <section v-if="loading" class="state">正在加载 Agent 配置…</section>
      <section v-else-if="error" class="state error">
        {{ error }} <button type="button" @click="load">重试</button>
      </section>
      <section v-else class="agent-grid">
        <article v-for="agent in visibleAgents" :key="`${agent.service}:${agent.agentKey}`" class="agent-card">
          <div class="card-head">
            <div>
              <span class="service">{{ serviceLabel(agent.service) }}</span>
              <h2>{{ agent.displayName }}</h2>
            </div>
            <span class="status" :class="{ off: !agent.enabled }">{{ agent.enabled ? '已启用' : '已停用' }}</span>
          </div>
          <p class="description">{{ agent.description }}</p>
          <div class="prompt-preview">{{ agent.systemPrompt }}</div>
          <dl>
            <div><dt>上下文消息</dt><dd>{{ agent.maxContextMessages }}</dd></div>
            <div><dt>上下文字数</dt><dd>{{ agent.maxContextChars }}</dd></div>
            <div><dt>输出上限</dt><dd>{{ agent.maxOutputChars }}</dd></div>
            <div><dt>最大步骤</dt><dd>{{ agent.maxSteps }}</dd></div>
          </dl>
          <footer>
            <small>{{ updateLabel(agent) }}</small>
            <button type="button" @click="edit(agent)">编辑配置</button>
          </footer>
        </article>
      </section>
    </main>

    <div v-if="editing" class="mask" @click.self="closeEditor">
      <form class="editor" @submit.prevent="save">
        <header>
          <div><small>{{ serviceLabel(editing.service) }}</small><h2>{{ editing.displayName }}</h2></div>
          <button type="button" aria-label="关闭" @click="closeEditor">×</button>
        </header>
        <label class="switch-row">
          <span><strong>启用 Agent</strong><small>停用后新的用户请求会返回明确提示</small></span>
          <input v-model="editing.enabled" type="checkbox" />
        </label>
        <label class="field">
          <span>系统提示词</span>
          <textarea v-model="editing.systemPrompt" rows="13" maxlength="20000" />
          <small>{{ editing.systemPrompt.length }} / 20000</small>
        </label>
        <div class="numbers">
          <label class="field"><span>上下文消息数</span><input v-model.number="editing.maxContextMessages" type="number" min="0" max="50" /></label>
          <label class="field"><span>上下文字符数</span><input v-model.number="editing.maxContextChars" type="number" min="1000" max="50000" /></label>
          <label class="field"><span>输出字符上限</span><input v-model.number="editing.maxOutputChars" type="number" min="500" max="100000" /></label>
          <label class="field"><span>最大步骤/检索数</span><input v-model.number="editing.maxSteps" type="number" min="1" max="20" /></label>
        </div>
        <p class="hint">最大步骤在科技图 Agent 中控制检索数量，在调研角色 Agent 中控制反思/执行深度。</p>
        <p v-if="editorError" class="editor-error">{{ editorError }}</p>
        <footer>
          <button type="button" @click="closeEditor">取消</button>
          <button class="primary" type="submit" :disabled="saving">{{ saving ? '保存中…' : '保存并生效' }}</button>
        </footer>
      </form>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { listAgentProfiles, saveAgentProfile } from '../api'
import type { AgentProfile, SaveAgentProfilePayload } from '../types'

type Filter = 'all' | AgentProfile['service']

const filters: Array<{ label: string; value: Filter }> = [
  { label: '全部 Agent', value: 'all' },
  { label: '科技图 AI', value: 'sparrow-ai' },
  { label: '产业链调研', value: 'sparrow-industry-chain' },
]
const agents = ref<AgentProfile[]>([])
const filter = ref<Filter>('all')
const loading = ref(true)
const error = ref('')
const editing = ref<AgentProfile | null>(null)
const saving = ref(false)
const editorError = ref('')

const visibleAgents = computed(() => filter.value === 'all'
  ? agents.value
  : agents.value.filter(agent => agent.service === filter.value))

onMounted(load)

async function load() {
  loading.value = true
  error.value = ''
  try {
    agents.value = await listAgentProfiles()
  } catch (cause: any) {
    error.value = cause.message || 'Agent 配置加载失败'
  } finally {
    loading.value = false
  }
}

function edit(agent: AgentProfile) {
  editing.value = { ...agent }
  editorError.value = ''
}

function closeEditor() {
  if (!saving.value) editing.value = null
}

async function save() {
  if (!editing.value) return
  editorError.value = ''
  if (editing.value.systemPrompt.trim().length < 20) {
    editorError.value = '系统提示词至少需要 20 个字符'
    return
  }
  saving.value = true
  try {
    const payload: SaveAgentProfilePayload = {
      agentKey: editing.value.agentKey,
      systemPrompt: editing.value.systemPrompt,
      enabled: editing.value.enabled,
      maxContextMessages: editing.value.maxContextMessages,
      maxContextChars: editing.value.maxContextChars,
      maxOutputChars: editing.value.maxOutputChars,
      maxSteps: editing.value.maxSteps,
    }
    const saved = await saveAgentProfile(editing.value.service, payload)
    const index = agents.value.findIndex(agent =>
      agent.service === saved.service && agent.agentKey === saved.agentKey)
    if (index >= 0) agents.value[index] = saved
    editing.value = null
  } catch (cause: any) {
    editorError.value = cause.message || '保存失败'
  } finally {
    saving.value = false
  }
}

function serviceLabel(service: AgentProfile['service']) {
  return service === 'sparrow-ai' ? '科技图 AI' : '产业链调研'
}

function updateLabel(agent: AgentProfile) {
  if (!agent.updatedAt) return '使用系统默认配置'
  const time = new Date(agent.updatedAt).toLocaleString('zh-CN', {
    timeZone: 'Asia/Shanghai', hour12: false,
  })
  return `更新：${time}${agent.updatedBy ? ` · #${agent.updatedBy}` : ''}`
}
</script>

<style scoped>
.admin-shell { min-height: 100%; background: var(--bg); }
.admin-page { max-width: 1180px; margin: 0 auto; padding: 30px 24px 64px; }
.page-head { display: flex; justify-content: space-between; gap: 24px; align-items: flex-start; }
.eyebrow, .service { color: var(--accent); font-size: 10px; font-weight: 800; letter-spacing: .12em; }
h1 { margin: 5px 0 8px; font-size: 29px; }
.page-head p { color: var(--ink-2); font-size: 13px; }
.model-link { border: 1px solid var(--line-strong); color: var(--ink); padding: 9px 12px; text-decoration: none; font-size: 12px; }
.toolbar { display: flex; gap: 6px; margin: 25px 0 18px; }
.toolbar button { border: 1px solid var(--line); background: var(--panel); padding: 8px 12px; cursor: pointer; }
.toolbar button.active { border-color: var(--ink); background: var(--ink); color: white; }
.toolbar .refresh { margin-left: auto; }
.state { padding: 32px; border: 1px solid var(--line); color: var(--ink-2); }
.state.error { color: var(--danger); }
.agent-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(330px, 1fr)); gap: 14px; }
.agent-card { display: flex; flex-direction: column; border: 1px solid var(--line); background: var(--panel); padding: 17px; }
.card-head { display: flex; justify-content: space-between; gap: 12px; }
.card-head h2 { margin-top: 5px; font-size: 17px; }
.status { height: fit-content; background: rgba(22, 163, 74, .1); color: #15803d; padding: 4px 7px; font-size: 10px; font-weight: 800; }
.status.off { background: rgba(220, 38, 38, .08); color: var(--danger); }
.description { min-height: 38px; margin: 11px 0; color: var(--ink-2); font-size: 12px; line-height: 1.55; }
.prompt-preview { height: 85px; overflow: hidden; border: 1px solid var(--line); background: var(--surface); padding: 9px; font-size: 11px; line-height: 1.55; color: var(--ink-2); }
dl { display: grid; grid-template-columns: repeat(4, 1fr); gap: 5px; margin: 12px 0; }
dl div { background: var(--surface); padding: 7px; }
dt { color: var(--muted); font-size: 9px; } dd { margin-top: 3px; font-size: 12px; font-weight: 700; }
.agent-card footer { display: flex; justify-content: space-between; align-items: center; gap: 10px; margin-top: auto; }
.agent-card footer small { color: var(--muted); font-size: 9px; }
.agent-card footer button, .editor footer button { border: 1px solid var(--line-strong); background: var(--panel); padding: 8px 11px; cursor: pointer; }
.mask { position: fixed; inset: 0; z-index: 300; display: grid; place-items: center; padding: 18px; background: rgba(0,0,0,.46); }
.editor { width: min(720px, 100%); max-height: 92vh; overflow: auto; border: 1px solid var(--ink); background: var(--panel); padding: 22px; }
.editor > header { display: flex; justify-content: space-between; border-bottom: 1px solid var(--line); padding-bottom: 14px; }
.editor > header button { border: 0; background: transparent; font-size: 25px; cursor: pointer; }
.switch-row { display: flex; justify-content: space-between; margin: 16px 0; padding: 12px; background: var(--surface); }
.switch-row span { display: grid; gap: 3px; } .switch-row small, .field small, .hint { color: var(--muted); font-size: 10px; }
.field { display: grid; gap: 6px; } .field > span { font-size: 11px; font-weight: 800; }
textarea, input[type='number'] { width: 100%; border: 1px solid var(--line-strong); background: var(--surface); color: var(--ink); padding: 10px; font: inherit; }
textarea { resize: vertical; line-height: 1.55; }
.numbers { display: grid; grid-template-columns: repeat(4, 1fr); gap: 9px; margin-top: 14px; }
.hint { margin-top: 8px; line-height: 1.5; }
.editor-error { margin-top: 12px; color: var(--danger); font-size: 12px; }
.editor > footer { display: flex; justify-content: flex-end; gap: 8px; margin-top: 18px; }
.editor footer .primary { background: var(--ink); color: white; }
@media (max-width: 700px) { .page-head { display: grid; } .numbers, dl { grid-template-columns: repeat(2, 1fr); } }
</style>
