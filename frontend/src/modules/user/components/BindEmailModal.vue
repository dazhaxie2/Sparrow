<template>
  <Teleport to="body">
    <div class="mask" @click.self="emit('close')">
      <form class="card" @submit.prevent="submit">
        <header><div><small>ACCOUNT SECURITY</small><h2>绑定邮箱</h2></div><button type="button" @click="emit('close')">×</button></header>
        <p>绑定后可以使用该邮箱和原密码登录。验证码仅用于本次绑定，不能作为登录验证码使用。</p>
        <label><span>邮箱地址</span><input v-model.trim="email" type="email" maxlength="128" autocomplete="email" placeholder="name@example.com" /></label>
        <label><span>邮箱验证码</span><div class="code-row"><input v-model.trim="code" inputmode="numeric" maxlength="6" autocomplete="one-time-code" placeholder="6 位验证码" /><button type="button" :disabled="sending || cooldown > 0 || !validEmail" @click="sendCode">{{ sending ? '发送中…' : cooldown > 0 ? `${cooldown}s` : '发送验证码' }}</button></div></label>
        <p v-if="message" class="message">{{ message }}</p>
        <p v-if="error" class="error">{{ error }}</p>
        <footer><button type="button" @click="emit('close')">取消</button><button class="primary" type="submit" :disabled="saving">{{ saving ? '绑定中…' : '确认绑定' }}</button></footer>
      </form>
    </div>
  </Teleport>
</template>

<script setup lang="ts">
import { computed, onUnmounted, ref } from 'vue'
import { sendBindEmailCode } from '../api'
import { useUserStore } from '../store'

const emit = defineEmits<{ close: []; bound: [] }>()
const user = useUserStore()
const email = ref('')
const code = ref('')
const sending = ref(false)
const saving = ref(false)
const cooldown = ref(0)
const message = ref('')
const error = ref('')
const validEmail = computed(() => /^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}$/.test(email.value))
let timer: ReturnType<typeof setInterval> | null = null

async function sendCode() {
  error.value = ''
  message.value = ''
  if (!validEmail.value) { error.value = '请输入有效邮箱'; return }
  sending.value = true
  try {
    await sendBindEmailCode(email.value)
    message.value = '验证码已发送，请在有效期内完成绑定。'
    cooldown.value = 60
    timer = setInterval(() => {
      cooldown.value -= 1
      if (cooldown.value <= 0 && timer) { clearInterval(timer); timer = null }
    }, 1000)
  } catch (cause: any) {
    error.value = cause.message || '验证码发送失败'
  } finally {
    sending.value = false
  }
}

async function submit() {
  error.value = ''
  if (!validEmail.value || !code.value) { error.value = '请填写邮箱和验证码'; return }
  saving.value = true
  try {
    await user.bindEmail(email.value, code.value)
    emit('bound')
    emit('close')
  } catch (cause: any) {
    error.value = cause.message || '邮箱绑定失败'
  } finally {
    saving.value = false
  }
}

onUnmounted(() => { if (timer) clearInterval(timer) })
</script>

<style scoped>
.mask { position: fixed; inset: 0; z-index: 400; display: grid; place-items: center; padding: 16px; background: rgba(0,0,0,.48); }
.card { width: min(440px, 100%); border: 1px solid var(--ink); border-radius: var(--radius); background: var(--panel); padding: 22px; box-shadow: var(--shadow-md); }
header { display: flex; justify-content: space-between; align-items: flex-start; border-bottom: 1px solid var(--line); padding-bottom: 13px; }
header small { color: var(--accent); font-size: 9px; font-weight: 800; letter-spacing: .12em; } h2 { margin-top: 4px; }
header button { border: 0; background: transparent; font-size: 24px; cursor: pointer; }
.card > p { margin: 14px 0; color: var(--ink-2); font-size: 12px; line-height: 1.6; }
label { display: grid; gap: 6px; margin-top: 12px; } label > span { font-size: 11px; font-weight: 800; }
input { width: 100%; height: 40px; border: 1px solid var(--line-strong); background: var(--surface); color: var(--ink); padding: 0 11px; }
.code-row { display: grid; grid-template-columns: 1fr auto; gap: 7px; }
.code-row button, footer button { border: 1px solid var(--line-strong); background: var(--panel); padding: 0 12px; cursor: pointer; }
.code-row button:disabled { opacity: .5; cursor: not-allowed; }
.card .message { color: #2563eb; } .card .error { color: var(--danger); }
footer { display: flex; justify-content: flex-end; gap: 8px; margin-top: 19px; } footer button { min-height: 36px; }
footer .primary { border-color: var(--accent); background: var(--accent); color: white; }
</style>
