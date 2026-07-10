<template>
  <Teleport to="body">
    <div class="modal" @click.self="$emit('close')">
      <form class="modal-box" @submit.prevent="handleSubmit">
        <div class="modal-head">
          <div>
            <span class="eyebrow">SPARROW ACCOUNT</span>
            <h3>登录 Sparrow</h3>
          </div>
          <button class="icon-btn" type="button" aria-label="关闭" @click="$emit('close')">×</button>
        </div>

        <div class="mode-switch" role="tablist" aria-label="登录方式">
          <button type="button" role="tab" :class="{ active: mode === 'password' }" @click="mode = 'password'">密码登录</button>
          <button type="button" role="tab" :class="{ active: mode === 'email' }" @click="mode = 'email'">邮箱验证码</button>
        </div>

        <!-- 密码登录 -->
        <template v-if="mode === 'password'">
          <label class="field">
            <span>用户名</span>
            <input v-model="username" type="text" placeholder="3-32 位" maxlength="32" autocomplete="username" />
          </label>
          <label class="field">
            <span>密码</span>
            <input
              v-model="password"
              type="password"
              placeholder="6-64 位"
              maxlength="64"
              autocomplete="current-password"
            />
          </label>
        </template>

        <!-- 邮箱验证码登录 -->
        <template v-else>
          <label class="field">
            <span>邮箱</span>
            <input v-model="email" type="email" placeholder="you@example.com" maxlength="128" autocomplete="email" />
          </label>
          <label class="field">
            <span>验证码</span>
            <div class="code-row">
              <input
                v-model="code"
                type="text"
                inputmode="numeric"
                placeholder="6 位验证码"
                maxlength="6"
                autocomplete="one-time-code"
              />
              <button
                class="btn code-btn"
                type="button"
                :disabled="cooldown > 0 || sendingCode || !emailValid"
                @click="handleSendCode"
              >
                {{ codeBtnText }}
              </button>
            </div>
          </label>
        </template>

        <p v-if="infoMessage" class="form-info">{{ infoMessage }}</p>
        <p v-if="errorMessage" class="form-error">{{ errorMessage }}</p>

        <div class="modal-actions">
          <button class="btn primary" type="submit" :disabled="submitting">
            {{ submitting ? '登录中…' : '登录' }}
          </button>
          <button v-if="mode === 'password'" class="btn" type="button" :disabled="submitting" @click="handleRegister">注册并登录</button>
          <button class="btn ghost" type="button" @click="$emit('close')">取消</button>
        </div>
      </form>
    </div>
  </Teleport>
</template>

<script setup lang="ts">
import { computed, onUnmounted, ref } from 'vue'
import { useUserStore } from '../store'
import { sendEmailCode } from '../api'

const emit = defineEmits<{ close: [] }>()
const user = useUserStore()

type Mode = 'password' | 'email'
const mode = ref<Mode>('password')

// 密码模式状态
const username = ref('')
const password = ref('')

// 邮箱模式状态
const email = ref('')
const code = ref('')
const cooldown = ref(0)
const sendingCode = ref(false)
const infoMessage = ref('')

const errorMessage = ref('')
const submitting = ref(false)

const emailValid = computed(() => /^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}$/.test(email.value))

const codeBtnText = computed(() => {
  if (sendingCode.value) return '发送中…'
  if (cooldown.value > 0) return `${cooldown.value}s`
  return '获取验证码'
})

let timer: ReturnType<typeof setInterval> | null = null

function startCooldown(seconds: number) {
  cooldown.value = seconds
  if (timer) clearInterval(timer)
  timer = setInterval(() => {
    cooldown.value -= 1
    if (cooldown.value <= 0 && timer) {
      clearInterval(timer)
      timer = null
    }
  }, 1000)
}

onUnmounted(() => {
  if (timer) clearInterval(timer)
})

async function handleSendCode() {
  errorMessage.value = ''
  infoMessage.value = ''
  if (!emailValid.value) {
    errorMessage.value = '邮箱格式不正确'
    return
  }
  sendingCode.value = true
  try {
    await sendEmailCode(email.value)
    infoMessage.value = '验证码已发送,请查收邮箱(5 分钟内有效)'
    startCooldown(60)
  } catch (error: any) {
    errorMessage.value = error.message
  } finally {
    sendingCode.value = false
  }
}

async function handleSubmit() {
  errorMessage.value = ''
  infoMessage.value = ''
  submitting.value = true
  try {
    if (mode.value === 'password') {
      await user.login(username.value, password.value)
    } else {
      if (!emailValid.value) throw new Error('邮箱格式不正确')
      if (!code.value) throw new Error('请输入验证码')
      await user.loginByEmail(email.value, code.value)
    }
    emit('close')
  } catch (error: any) {
    errorMessage.value = error.message
  } finally {
    submitting.value = false
  }
}

async function handleRegister() {
  errorMessage.value = ''
  infoMessage.value = ''
  submitting.value = true
  try {
    await user.register(username.value, password.value)
    emit('close')
  } catch (error: any) {
    errorMessage.value = error.message
  } finally {
    submitting.value = false
  }
}
</script>

<style scoped>
.modal {
  position: fixed;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  background: rgba(0, 0, 0, 0.42);
  backdrop-filter: blur(2px);
  z-index: 100;
}

.modal-box {
  width: min(420px, calc(100vw - 32px));
  border: 1px solid var(--ink);
  background: var(--panel);
  box-shadow: var(--shadow-md);
  padding: 22px;
}

.modal-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  padding-bottom: 16px;
  border-bottom: 1px solid var(--line);
}

.eyebrow {
  color: var(--accent);
  font-size: 11px;
  font-weight: 800;
  letter-spacing: 0.12em;
}

.modal-head h3 {
  margin-top: 6px;
  font-size: 24px;
  letter-spacing: 0;
}

.icon-btn {
  width: 30px;
  height: 30px;
  border: 1px solid var(--line-strong);
  background: var(--panel);
  color: var(--ink);
  cursor: pointer;
}

/* 登录方式切换 */
.mode-switch {
  display: inline-flex;
  margin-top: 16px;
  border: 1px solid var(--line-strong);
  background: var(--surface);
}

.mode-switch button {
  border: 0;
  background: transparent;
  color: var(--ink-2);
  padding: 8px 16px;
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 0.04em;
  cursor: pointer;
}

.mode-switch button.active {
  background: var(--ink);
  color: var(--bg);
}

.field {
  display: grid;
  gap: 7px;
  margin-top: 14px;
}

.field span {
  color: var(--ink-2);
  font-size: 12px;
  font-weight: 800;
  letter-spacing: 0.08em;
}

.field input {
  width: 100%;
  height: 40px;
  border: 1px solid var(--line-strong);
  background: var(--surface);
  color: var(--ink);
  padding: 0 11px;
  font-size: 14px;
  outline: none;
}

.field input:focus {
  border-color: var(--ink);
  background: var(--panel);
}

.code-row {
  display: flex;
  gap: 8px;
}

.code-row input {
  flex: 1;
  min-width: 0;
}

.code-btn {
  white-space: nowrap;
  min-height: 40px;
}

.code-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.form-error {
  margin-top: 12px;
  border: 1px solid rgba(220, 38, 38, 0.32);
  background: rgba(220, 38, 38, 0.06);
  color: var(--danger);
  padding: 9px 10px;
  font-size: 13px;
  line-height: 1.6;
}

.form-info {
  margin-top: 12px;
  border: 1px solid var(--line);
  background: var(--surface);
  color: var(--ink-2);
  padding: 9px 10px;
  font-size: 13px;
  line-height: 1.6;
}

.modal-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 18px;
}

.btn {
  min-height: 36px;
  border: 1px solid var(--line-strong);
  background: var(--panel);
  color: var(--ink);
  padding: 0 13px;
  font-size: 13px;
  cursor: pointer;
}

.btn:hover:not(:disabled),
.icon-btn:hover {
  border-color: var(--ink);
}

.btn:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.btn.primary {
  border-color: var(--ink);
  background: var(--ink);
  color: var(--bg);
  font-weight: 800;
}

.btn.ghost {
  background: transparent;
}
</style>
