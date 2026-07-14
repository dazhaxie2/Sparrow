<template>
  <Teleport to="body">
    <div class="sparrow-overlay" @mousedown="dismiss.onMaskMousedown" @mouseup="dismiss.onMaskMouseup">
      <form class="sparrow-modal card" @submit.prevent="handleSubmit">
        <button class="close-btn" type="button" aria-label="跳过" @click="$emit('skip')">×</button>

        <div class="head">
          <KeyRound :size="26" />
          <strong>{{ user.profile?.passwordSet ? '修改登录密码' : '设置登录密码' }}</strong>
          <p>{{ user.profile?.passwordSet ? '为保护账号安全，修改前需要验证当前密码。' : '设置密码后,也可以用密码登录。' }}</p>
        </div>

        <label v-if="user.profile?.passwordSet" class="field">
          <input
            v-model="currentPassword"
            :type="showCurrentPassword ? 'text' : 'password'"
            placeholder="请输入当前密码"
            maxlength="64"
            autocomplete="current-password"
            autofocus
          />
          <button class="toggle-pwd" type="button" @click="showCurrentPassword = !showCurrentPassword" :aria-label="showCurrentPassword ? '隐藏密码' : '显示密码'">
            <Eye v-if="showCurrentPassword" :size="16" />
            <EyeOff v-else :size="16" />
          </button>
        </label>
        <label class="field">
          <input
            v-model.trim="password"
            :type="showPassword ? 'text' : 'password'"
            placeholder="设置密码(6-64 位)"
            maxlength="64"
            autocomplete="new-password"
            :autofocus="!user.profile?.passwordSet"
          />
          <button class="toggle-pwd" type="button" @click="showPassword = !showPassword" :aria-label="showPassword ? '隐藏密码' : '显示密码'">
            <Eye v-if="showPassword" :size="16" />
            <EyeOff v-else :size="16" />
          </button>
        </label>
        <label class="field">
          <input
            v-model.trim="confirm"
            :type="showConfirm ? 'text' : 'password'"
            placeholder="再次输入密码确认"
            maxlength="64"
            autocomplete="new-password"
          />
          <button class="toggle-pwd" type="button" @click="showConfirm = !showConfirm" :aria-label="showConfirm ? '隐藏密码' : '显示密码'">
            <Eye v-if="showConfirm" :size="16" />
            <EyeOff v-else :size="16" />
          </button>
        </label>

        <transition name="msg">
          <p v-if="errorMessage" class="error-msg">{{ errorMessage }}</p>
        </transition>

        <button class="primary-btn" type="submit" :disabled="submitting">
          <LoaderCircle v-if="submitting" class="spin" :size="16" />
          <span>{{ submitting ? '设置中…' : '设置密码' }}</span>
        </button>
        <button class="skip-btn" type="button" :disabled="submitting" @click="$emit('skip')">跳过,以后再说</button>
      </form>
    </div>
  </Teleport>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { Eye, EyeOff, KeyRound, LoaderCircle } from '@lucide/vue'
import { useUserStore } from '../store'
import { setPassword as apiSetPassword } from '../api'
import { useDismissableOverlay } from '../../../shared/composables/useDismissableOverlay'

const emit = defineEmits<{ done: []; skip: [] }>()
const user = useUserStore()
const dismiss = useDismissableOverlay(() => emit('skip'))

const currentPassword = ref('')
const password = ref('')
const confirm = ref('')
const showCurrentPassword = ref(false)
const showPassword = ref(false)
const showConfirm = ref(false)
const errorMessage = ref('')
const submitting = ref(false)

async function handleSubmit() {
  errorMessage.value = ''
  if (user.profile?.passwordSet && !currentPassword.value) {
    errorMessage.value = '请输入当前密码'
    return
  }
  if (!password.value || password.value.length < 6 || password.value.length > 64) {
    errorMessage.value = '密码长度需在 6 到 64 个字符之间'
    return
  }
  if (password.value !== confirm.value) {
    errorMessage.value = '两次输入的密码不一致'
    return
  }
  submitting.value = true
  try {
    const result = await apiSetPassword(currentPassword.value, password.value, confirm.value)
    user.profile = result.profile
    user.token = result.token
    localStorage.setItem('sparrow_token', result.token)
    emit('done')
  } catch (error: any) {
    errorMessage.value = error.message
  } finally {
    submitting.value = false
  }
}
</script>

<style scoped>
.card {
  position: relative;
  width: min(380px, 100%);
  padding: 32px 28px 22px;
  display: flex;
  flex-direction: column;
  gap: 12px;
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

.head {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
  text-align: center;
  margin-bottom: 6px;
  color: var(--ink);
}

.head p {
  color: var(--muted);
  font-size: 12px;
  line-height: 1.6;
}

.field { display: block; position: relative; }

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

.field input::placeholder { color: var(--muted); }

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

.error-msg {
  color: var(--danger);
  font-size: 12px;
  line-height: 1.5;
}

.msg-enter-active, .msg-leave-active { transition: opacity 0.18s ease; }
.msg-enter-from, .msg-leave-to { opacity: 0; }

.primary-btn {
  margin-top: 4px;
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

.primary-btn:not(:disabled):hover { background: #f4511e; }
.primary-btn:not(:disabled):active { transform: scale(0.99); }
.primary-btn:disabled { opacity: 0.7; cursor: not-allowed; }

.skip-btn {
  border: 0;
  background: transparent;
  color: var(--muted);
  font-size: 12px;
  cursor: pointer;
  padding: 6px;
}

.skip-btn:not(:disabled):hover { color: var(--ink-2); }
.skip-btn:disabled { opacity: 0.6; cursor: not-allowed; }

.spin { animation: spin 1s linear infinite; }

@keyframes spin { to { transform: rotate(360deg); } }
</style>
