<template>
  <div class="chain-list-shell">
    <main class="chain-list-page">
      <header class="page-header">
        <h1>产业链调研</h1>
        <p>围绕产品、企业、地区和时间范围创建调研工作台，通过 Multi-Agent 生成带来源的互动图谱和深度报告。</p>
      </header>

      <section class="my-research">
        <div class="section-heading">
          <div><span>MULTI-AGENT RESEARCH</span><h2>我的调研工作台</h2></div>
          <button v-if="user.profile" class="new-button" type="button" @click="openCreate"><Plus :size="15" />新建调研</button>
        </div>
        <div v-if="!user.profile" class="research-login">
          <Sparkles :size="23" />
          <div><strong>登录后创建自己的产业链工作台</strong><p>与 Multi-Agent 对话、联网调研，并持续维护带来源的互动图谱和深度报告。</p></div>
          <button type="button" @click="goLogin">登录 / 注册</button>
        </div>
        <div v-else-if="researchLoading" class="state-box"><LoaderCircle class="spin" :size="20" /><span>加载我的调研卡片</span></div>
        <div v-else-if="researchError" class="state-box error"><AlertTriangle :size="20" /><span>{{ researchError }}</span><button type="button" @click="loadResearchCards">重试</button></div>
        <div v-else class="chain-grid research-grid">
          <button class="create-card" type="button" @click="openCreate">
            <span><Plus :size="20" /></span><strong>新建产业链调研</strong><small>先和 Agent 对话收窄问题，再启动联网研究</small>
          </button>
          <article v-for="card in researchCards" :key="card.id" class="chain-card research-card" @click="openWorkbench(card.id)">
            <div class="card-top">
              <span class="status-badge" :class="card.status.toLowerCase()">{{ statusText(card.status) }}</span>
              <button class="more-button" type="button" title="编辑卡片" @click.stop="openEdit(card)"><Pencil :size="14" /></button>
              <button class="more-button danger" type="button" title="删除卡片" @click.stop="askDelete(card)"><Trash2 :size="14" /></button>
              <ArrowRight :size="16" />
            </div>
            <h2>{{ card.title }}</h2>
            <p>{{ card.brief || '进入工作台，与 Agent 补充调研范围。' }}</p>
            <div v-if="card.status === 'RESEARCHING'" class="card-progress"><i :style="{ width: `${card.progress}%` }"></i><span>{{ card.progress }}%</span></div>
            <div class="card-meta"><span>{{ card.nodeCount }} 节点</span><span>{{ card.edgeCount }} 关系</span><time>{{ formatDate(card.updatedAt) }}</time></div>
          </article>
        </div>
      </section>
    </main>

    <div v-if="editorOpen" class="modal-backdrop" @click.self="closeEditor">
      <form class="card-editor" @submit.prevent="saveCard">
        <div class="modal-title"><div><span>MULTI-AGENT</span><h2>{{ editingId ? '编辑产业链调研' : '新建产业链调研' }}</h2></div><button type="button" @click="closeEditor"><X :size="17" /></button></div>
        <label>调研主题<input v-model="formTitle" maxlength="120" required placeholder="例如：中国人形机器人产业链" /></label>
        <label>初始范围<textarea v-model="formBrief" maxlength="2000" rows="5" placeholder="关注地区、时间范围、产品环节、重点公司或你想验证的问题…"></textarea></label>
        <p v-if="editorError" class="form-error">{{ editorError }}</p>
        <div class="modal-actions"><button type="button" @click="closeEditor">取消</button><button class="save" type="submit" :disabled="saving || !formTitle.trim()"><LoaderCircle v-if="saving" class="spin" :size="14" />{{ editingId ? '保存' : '创建并进入工作台' }}</button></div>
      </form>
    </div>

    <div v-if="deletingCard" class="modal-backdrop" @click.self="deletingCard = null">
      <section class="delete-dialog"><AlertTriangle :size="22" /><h2>删除“{{ deletingCard.title }}”？</h2><p>对话、调研报告、图谱和来源将一并删除，且无法恢复。</p><div class="modal-actions"><button type="button" @click="deletingCard = null">取消</button><button class="delete" type="button" :disabled="saving" @click="removeCard">确认删除</button></div></section>
    </div>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { AlertTriangle, ArrowRight, LoaderCircle, Pencil, Plus, Sparkles, Trash2, X } from '@lucide/vue'
import { useUserStore } from '../../user/store'
import { createResearchCard, deleteResearchCard, fetchResearchCards, updateResearchCard } from '../api'
import type { ResearchCardSummary } from '../model/types'

const router = useRouter()
const user = useUserStore()
const researchCards = ref<ResearchCardSummary[]>([])
const researchLoading = ref(false)
const researchError = ref('')
const editorOpen = ref(false)
const editingId = ref<number | null>(null)
const formTitle = ref('')
const formBrief = ref('')
const editorError = ref('')
const saving = ref(false)
const deletingCard = ref<ResearchCardSummary | null>(null)

async function loadResearchCards() {
  if (!user.profile) { researchCards.value = []; return }
  researchLoading.value = true
  researchError.value = ''
  try { researchCards.value = await fetchResearchCards() }
  catch (e: any) { researchError.value = e.message || '调研卡片加载失败' }
  finally { researchLoading.value = false }
}

function openCreate() { editingId.value = null; formTitle.value = ''; formBrief.value = ''; editorError.value = ''; editorOpen.value = true }
function openEdit(card: ResearchCardSummary) { editingId.value = card.id; formTitle.value = card.title; formBrief.value = card.brief || ''; editorError.value = ''; editorOpen.value = true }
function closeEditor() { if (!saving.value) editorOpen.value = false }

async function saveCard() {
  const title = formTitle.value.trim()
  if (!title || saving.value) return
  saving.value = true
  editorError.value = ''
  try {
    if (editingId.value) {
      const detail = await updateResearchCard(editingId.value, title, formBrief.value.trim())
      const index = researchCards.value.findIndex(card => card.id === editingId.value)
      if (index >= 0) researchCards.value[index] = detail.card
      editorOpen.value = false
    } else {
      const detail = await createResearchCard(title, formBrief.value.trim())
      editorOpen.value = false
      await router.push(`/chains/${detail.card.id}`)
    }
  } catch (e: any) { editorError.value = e.message || '保存失败' }
  finally { saving.value = false }
}

function askDelete(card: ResearchCardSummary) { deletingCard.value = card }
async function removeCard() {
  if (!deletingCard.value || saving.value) return
  saving.value = true
  researchError.value = ''
  try {
    const id = deletingCard.value.id
    await deleteResearchCard(id)
    researchCards.value = researchCards.value.filter(card => card.id !== id)
    deletingCard.value = null
  } catch (e: any) { researchError.value = e.message || '删除失败'; deletingCard.value = null }
  finally { saving.value = false }
}

function openWorkbench(id: number) { void router.push(`/chains/${id}`) }
function goLogin() { void router.push({ path: '/', query: { open: 'login' } }) }
function statusText(status: string) { return ({ DRAFT: '待调研', RESEARCHING: '调研中', COMPLETED: '已完成', FAILED: '需重试' } as Record<string, string>)[status] || status }
function formatDate(value: string) { return new Date(value).toLocaleDateString('zh-CN', { month: '2-digit', day: '2-digit' }) }

watch(() => user.profile?.id, () => void loadResearchCards())
onMounted(() => { void loadResearchCards() })
</script>

<style scoped>
.chain-list-shell { min-height: 100%; background: var(--surface); }
.chain-list-page { max-width: 1080px; margin: 0 auto; padding: 40px 24px 60px; }
.page-header h1 { font-size: 28px; letter-spacing: -0.01em; }
.page-header p { margin-top: 10px; color: var(--ink-2); font-size: 14px; line-height: 1.8; }
.section-heading { display: flex; align-items: flex-end; justify-content: space-between; gap: 16px; margin-top: 32px; }
.section-heading span { color: var(--accent); font-size: 9px; font-weight: 900; letter-spacing: .14em; }
.section-heading h2 { margin-top: 3px; font-size: 17px; }
.my-research { margin-top: 32px; }
.new-button { display: inline-flex; align-items: center; gap: 6px; min-height: 34px; padding: 0 12px; border: 1px solid var(--accent); border-radius: 7px; background: var(--accent); color: white; cursor: pointer; }
.state-box { display: flex; align-items: center; gap: 10px; margin-top: 18px; padding: 18px; border: 1px solid var(--line); border-radius: var(--radius); color: var(--ink-2); font-size: 14px; }
.state-box.error { border-color: var(--danger); color: var(--danger); }
.state-box button { margin-left: auto; border: 1px solid var(--line-strong); background: var(--panel); padding: 6px 14px; color: var(--ink); cursor: pointer; }
.chain-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(280px, 1fr)); gap: 18px; margin-top: 14px; }
.chain-card { display: flex; flex-direction: column; gap: 10px; padding: 22px; border: 1px solid var(--line); border-radius: var(--radius); background: var(--panel); color: var(--ink); text-decoration: none; transition: transform .16s ease, border-color .16s ease, box-shadow .16s ease; }
.chain-card:hover { transform: translateY(-3px); border-color: var(--chain-color, var(--accent)); box-shadow: var(--shadow-md); }
.card-top { display: flex; align-items: center; color: var(--chain-color, var(--accent)); }
.chain-card h2 { font-size: 18px; }
.chain-card p { color: var(--ink-2); font-size: 13px; line-height: 1.7; display: -webkit-box; -webkit-line-clamp: 3; -webkit-box-orient: vertical; overflow: hidden; }
.create-card { min-height: 215px; display: grid; place-content: center; justify-items: center; gap: 8px; padding: 22px; border: 1px dashed var(--line-strong); border-radius: var(--radius); background: rgba(255,255,255,.55); color: var(--ink); cursor: pointer; }
.create-card:hover { border-color: var(--accent); background: #fff; }
.create-card > span { display: grid; place-items: center; width: 40px; height: 40px; border-radius: 50%; background: rgba(255,87,34,.08); color: var(--accent); }
.create-card small { max-width: 220px; color: var(--muted); line-height: 1.5; }
.research-card { cursor: pointer; --chain-color: var(--accent); }
.research-card .card-top { gap: 5px; }
.status-badge { margin-right: auto; padding: 4px 9px; border-radius: 99px; background: #eef1f3; color: var(--ink-2); font-size: 10px; font-weight: 800; }
.status-badge.researching { background: #fff3e9; color: #e65100; }
.status-badge.completed { background: #eaf7ee; color: #237a3b; }
.status-badge.failed { background: #fff0f0; color: var(--danger); }
.more-button { display: grid; place-items: center; width: 27px; height: 27px; border: 0; border-radius: 5px; background: transparent; color: var(--muted); cursor: pointer; }
.more-button:hover { background: var(--surface); color: var(--ink); }
.more-button.danger:hover { color: var(--danger); }
.card-meta { display: flex; gap: 10px; margin-top: auto; padding-top: 8px; border-top: 1px solid var(--line); color: var(--muted); font-size: 10px; }
.card-meta time { margin-left: auto; }
.card-progress { position: relative; height: 5px; border-radius: 99px; background: #eceff1; }
.card-progress i { display: block; height: 100%; background: var(--accent); }
.card-progress span { position: absolute; right: 0; top: -18px; color: var(--muted); font-size: 9px; }
.research-login { display: flex; align-items: center; gap: 14px; margin-top: 14px; padding: 22px; border: 1px dashed var(--line-strong); border-radius: var(--radius); background: rgba(255,255,255,.55); }
.research-login > svg { flex: none; color: var(--accent); }
.research-login div { flex: 1; }
.research-login p { margin: 4px 0 0; color: var(--muted); font-size: 12px; }
.research-login button { min-height: 34px; border: 1px solid var(--ink); background: var(--ink); color: white; padding: 0 13px; cursor: pointer; }
.modal-backdrop { position: fixed; inset: 0; z-index: 500; display: grid; place-items: center; padding: 20px; background: rgba(24,28,33,.38); backdrop-filter: blur(3px); }
.card-editor, .delete-dialog { width: min(520px, 100%); display: grid; gap: 18px; padding: 22px; border: 1px solid var(--line); border-radius: 10px; background: #fff; box-shadow: var(--shadow-md); }
.modal-title { display: flex; justify-content: space-between; gap: 12px; }
.modal-title span { color: var(--accent); font-size: 9px; font-weight: 900; letter-spacing: .14em; }
.modal-title h2, .delete-dialog h2 { margin: 3px 0 0; font-size: 20px; }
.modal-title > button { display: grid; place-items: center; width: 30px; height: 30px; border: 0; background: transparent; cursor: pointer; }
.card-editor label { display: grid; gap: 7px; color: var(--ink-2); font-size: 12px; font-weight: 700; }
.card-editor input, .card-editor textarea { border: 1px solid var(--line); border-radius: 7px; outline: none; padding: 10px 11px; color: var(--ink); font: inherit; font-size: 13px; resize: vertical; }
.card-editor input:focus, .card-editor textarea:focus { border-color: var(--accent); box-shadow: 0 0 0 3px rgba(255,87,34,.08); }
.form-error { margin: -6px 0 0; color: var(--danger); font-size: 12px; }
.modal-actions { display: flex; justify-content: flex-end; gap: 8px; }
.modal-actions button { display: inline-flex; align-items: center; gap: 6px; min-height: 35px; padding: 0 14px; border: 1px solid var(--line); border-radius: 6px; background: #fff; cursor: pointer; }
.modal-actions .save { border-color: var(--accent); background: var(--accent); color: #fff; }
.modal-actions .delete { border-color: var(--danger); background: var(--danger); color: #fff; }
.delete-dialog { justify-items: center; text-align: center; }
.delete-dialog > svg { color: var(--danger); }
.delete-dialog p { margin: -8px 0 0; color: var(--ink-2); font-size: 13px; }
.delete-dialog .modal-actions { justify-content: center; }
.spin { animation: spin .9s linear infinite; }
@keyframes spin { to { transform: rotate(360deg); } }
@media (max-width: 720px) { .research-login { align-items: flex-start; flex-wrap: wrap; } .research-login div { flex-basis: calc(100% - 50px); } }
</style>
