<template>
  <Teleport to="body">
    <div class="sparrow-overlay" @mousedown="dismiss.onMaskMousedown" @mouseup="dismiss.onMaskMouseup">
      <form class="sparrow-modal login-card" @submit.prevent="handleSubmit">
        <button class="close-btn" type="button" aria-label="关闭" @click="$emit('close')">×</button>

        <!-- 品牌区 -->
        <div class="brand">
          <div class="logo">S</div>
          <strong>Sparrow</strong>
          <span class="brand-sub">科技图 · Human Technology Tree</span>
        </div>

        <!-- Tab 切换 -->
        <div class="tabs" role="tablist" aria-label="登录方式">
          <button type="button" role="tab" :class="{ active: mode === 'email' }" @click="mode = 'email'">邮箱验证码登录</button>
          <button type="button" role="tab" :class="{ active: mode === 'password' }" @click="mode = 'password'">账号密码登录</button>
        </div>

        <!-- 邮箱验证码登录 -->
        <template v-if="mode === 'email'">
          <label class="field">
            <input
              v-model="email"
              type="email"
              placeholder="请输入邮箱地址"
              maxlength="128"
              autocomplete="email"
            />
          </label>
          <label class="field code-field">
            <input
              v-model="code"
              type="text"
              inputmode="numeric"
              placeholder="请输入验证码"
              maxlength="6"
              autocomplete="one-time-code"
            />
            <button
              class="inline-code-btn"
              type="button"
              :disabled="cooldown > 0 || sendingCode || !emailValid"
              @click="handleSendCode"
            >
              <LoaderCircle v-if="sendingCode" class="spin" :size="13" />
              <span v-else>{{ codeBtnText }}</span>
            </button>
          </label>
        </template>

        <!-- 密码登录 -->
        <template v-else>
          <label class="field">
            <input v-model="username" type="text" placeholder="用户名或已绑定邮箱" maxlength="128" autocomplete="username" />
          </label>
          <label class="field">
            <input
              v-model="password"
              :type="showPassword ? 'text' : 'password'"
              placeholder="请输入密码"
              maxlength="64"
              autocomplete="current-password"
            />
            <button class="toggle-pwd" type="button" @click="showPassword = !showPassword" :aria-label="showPassword ? '隐藏密码' : '显示密码'">
              <Eye v-if="showPassword" :size="16" />
              <EyeOff v-else :size="16" />
            </button>
          </label>
        </template>

        <transition name="msg">
          <p v-if="infoMessage" class="info-msg">{{ infoMessage }}</p>
        </transition>
        <transition name="msg">
          <p v-if="errorMessage" class="error-msg">{{ errorMessage }}</p>
        </transition>

        <button class="primary-btn" type="submit" :disabled="submitting">
          <LoaderCircle v-if="submitting" class="spin" :size="16" />
          <span>{{ submitting ? '登录中…' : mode === 'password' ? '登录' : '登录 / 注册' }}</span>
        </button>

        <p class="agreement">
          登录即表示同意 Sparrow《<a href="#" @click.prevent>用户协议</a>》与《<a href="#" @click.prevent>隐私政策</a>》
        </p>
      </form>
    </div>
  </Teleport>
</template>

<script setup lang="ts">
import { computed, onUnmounted, ref } from 'vue'
import { Eye, EyeOff, LoaderCircle } from '@lucide/vue'
import { useUserStore } from '../store'
import { sendEmailCode } from '../api'
import { useDismissableOverlay } from '../../../shared/composables/useDismissableOverlay'

const emit = defineEmits<{ close: [] }>()
const user = useUserStore()
const dismiss = useDismissableOverlay(() => emit('close'))

type Mode = 'email' | 'password'
// 默认邮箱验证码(产品主推方式)
const mode = ref<Mode>('email')

// 密码模式状态
const username = ref('')
const password = ref('')
const showPassword = ref(false)

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
  if (cooldown.value > 0) return `${cooldown.value}s 后重发`
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
</script>

<style scoped>
.login-card {
  width: min(380px, 100%);
  padding: 36px 28px 24px;
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.close-btn {
  position: absolute;
  top: 14px;
  right: 14px;
  width: 30px;
  height: 30px;
  border: 0;
  background: transparent;
  color: var(--muted);
  font-size: 22px;
  line-height: 1;
  cursor: pointer;
  border-radius: 50%;
  transition: background 0.16s ease, color 0.16s ease;
}

.close-btn:hover {
  background: var(--surface-2);
  color: var(--ink);
}

/* 品牌区 */
.brand {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
  margin-bottom: 8px;
}

.logo {
  width: 52px;
  height: 52px;
  border-radius: 14px;
  background: var(--ink);
  color: var(--bg);
  font-size: 26px;
  font-weight: 800;
  display: flex;
  align-items: center;
  justify-content: center;
  margin-bottom: 4px;
}

.brand strong {
  font-size: 20px;
  letter-spacing: 0.04em;
}

.brand-sub {
  color: var(--muted);
  font-size: 11px;
  letter-spacing: 0.06em;
}

/* Tab 切换 */
.tabs {
  display: flex;
  gap: 24px;
  border-bottom: 1px solid var(--line);
  margin: 4px 0 6px;
}

.tabs button {
  flex: 1;
  border: 0;
  background: transparent;
  padding: 10px 0;
  color: var(--muted);
  font-size: 14px;
  font-weight: 600;
  cursor: pointer;
  position: relative;
  transition: color 0.16s ease;
}

.tabs button.active {
  color: var(--ink);
}

.tabs button.active::after {
  content: '';
  position: absolute;
  left: 0;
  right: 0;
  bottom: -1px;
  height: 2px;
  background: var(--accent);
  border-radius: 1px;
}

/* 输入字段 */
.field {
  display: block;
  position: relative;
}

.field input {
  width: 100%;
  height: 46px;
  border: 1px solid var(--line);
  border-radius: var(--radius-sm);
  background: var(--surface);
  color: var(--ink);
  padding: 0 14px;
  font-size: 14px;
  outline: none;
  transition: border-color 0.16s ease, background 0.16s ease, box-shadow 0.16s ease;
}

.field input::placeholder {
  color: var(--muted);
}

.field input:focus {
  border-color: var(--ink);
  background: var(--panel);
  box-shadow: 0 0 0 3px rgba(0, 0, 0, 0.04);
}

.toggle-pwd {
  position: absolute;
  top: 50%;
  right: 8px;
  transform: translateY(-50%);
  border: 0;
  background: transparent;
  color: var(--muted);
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  padding: 4px;
  border-radius: 4px;
  transition: color 0.16s ease;
}

.toggle-pwd:hover {
  color: var(--ink);
}

/* 验证码行 */
.code-field input {
  padding-right: 110px;
}

.inline-code-btn {
  position: absolute;
  top: 50%;
  right: 6px;
  transform: translateY(-50%);
  height: 34px;
  padding: 0 12px;
  border: 0;
  border-radius: 6px;
  background: var(--ink);
  color: var(--bg);
  font-size: 12px;
  font-weight: 600;
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  gap: 4px;
  white-space: nowrap;
  transition: opacity 0.16s ease;
}

.inline-code-btn:disabled {
  background: var(--line-strong);
  cursor: not-allowed;
  opacity: 0.8;
}

.inline-code-btn:not(:disabled):hover {
  opacity: 0.85;
}

/* 消息提示 */
.info-msg {
  color: var(--blue);
  font-size: 12px;
  line-height: 1.5;
}

.error-msg {
  color: var(--danger);
  font-size: 12px;
  line-height: 1.5;
}

.msg-enter-active,
.msg-leave-active {
  transition: opacity 0.18s ease;
}

.msg-enter-from,
.msg-leave-to {
  opacity: 0;
}

/* 主按钮 */
.primary-btn {
  margin-top: 6px;
  height: 46px;
  border: 0;
  border-radius: var(--radius-sm);
  background: var(--accent);
  color: #fff;
  font-size: 15px;
  font-weight: 700;
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  transition: background 0.16s ease, transform 0.08s ease;
}

.primary-btn:not(:disabled):hover {
  background: #f4511e;
}

.primary-btn:not(:disabled):active {
  transform: scale(0.99);
}

.primary-btn:disabled {
  opacity: 0.7;
  cursor: not-allowed;
}

/* 协议 */
.agreement {
  text-align: center;
  color: var(--muted);
  font-size: 11px;
  line-height: 1.6;
  margin-top: 4px;
}

.agreement a {
  color: var(--ink-2);
  text-decoration: none;
}

.agreement a:hover {
  color: var(--ink);
  text-decoration: underline;
}

.spin {
  animation: spin 1s linear infinite;
}

@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}

@media (max-width: 420px) {
  .login-card {
    padding: 28px 20px 20px;
  }

  .tabs {
    gap: 12px;
  }

  .tabs button {
    font-size: 13px;
  }
}
</style>
