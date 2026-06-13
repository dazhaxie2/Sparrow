<template>
  <Teleport to="body">
    <div class="modal" @click.self="$emit('close')">
      <form class="modal-box" @submit.prevent="handleLogin">
        <div class="modal-head">
          <div>
            <span class="eyebrow">SPARROW ACCOUNT</span>
            <h3>登录 Sparrow</h3>
          </div>
          <button class="icon-btn" type="button" aria-label="关闭" @click="$emit('close')">×</button>
        </div>

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

        <p v-if="errorMessage" class="form-error">{{ errorMessage }}</p>

        <div class="modal-actions">
          <button class="btn primary" type="submit">登录</button>
          <button class="btn" type="button" @click="handleRegister">注册并登录</button>
          <button class="btn ghost" type="button" @click="$emit('close')">取消</button>
        </div>
      </form>
    </div>
  </Teleport>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { useUserStore } from '../store'

const emit = defineEmits<{ close: [] }>()
const user = useUserStore()
const username = ref('')
const password = ref('')
const errorMessage = ref('')

async function handleLogin() {
  errorMessage.value = ''
  try {
    await user.login(username.value, password.value)
    emit('close')
  } catch (error: any) {
    errorMessage.value = error.message
  }
}

async function handleRegister() {
  errorMessage.value = ''
  try {
    await user.register(username.value, password.value)
    emit('close')
  } catch (error: any) {
    errorMessage.value = error.message
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

.form-error {
  margin-top: 12px;
  border: 1px solid rgba(220, 38, 38, 0.32);
  background: rgba(220, 38, 38, 0.06);
  color: var(--danger);
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

.btn:hover,
.icon-btn:hover {
  border-color: var(--ink);
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
