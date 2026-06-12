<template>
  <Teleport to="body">
    <div class="modal" @click.self="$emit('close')">
      <div class="modal-box">
        <h3>登录 Sparrow</h3>
        <input v-model="username" type="text" placeholder="用户名(3-32位)" maxlength="32" />
        <input v-model="password" type="password" placeholder="密码(6-64位)" maxlength="64" />
        <div class="modal-actions">
          <button class="btn primary" @click="handleLogin">登录</button>
          <button class="btn" @click="handleRegister">注册并登录</button>
          <button class="btn ghost" @click="$emit('close')">取消</button>
        </div>
      </div>
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

async function handleLogin() {
  try { await user.login(username.value, password.value); emit('close') }
  catch (e: any) { alert(e.message) }
}

async function handleRegister() {
  try { await user.register(username.value, password.value); emit('close') }
  catch (e: any) { alert(e.message) }
}
</script>

<style scoped>
.modal {
  position: fixed; inset: 0; background: rgba(5, 9, 18, 0.7);
  display: flex; align-items: center; justify-content: center; z-index: 100;
}
.modal-box {
  width: 360px; background: var(--bg-2); border: 1px solid var(--line);
  border-radius: 14px; padding: 24px;
}
.modal-box h3 { margin-bottom: 14px; }
.modal-box input {
  width: 100%; margin-bottom: 10px; background: var(--bg);
  border: 1px solid var(--line); border-radius: 8px; color: var(--ink);
  padding: 10px 12px; font-size: 14px; outline: none;
}
.modal-box input:focus { border-color: var(--accent); }
.modal-actions { display: flex; gap: 8px; margin-top: 8px; }
.btn {
  background: #1d2a44; color: var(--ink); border: 1px solid var(--line);
  border-radius: 8px; padding: 7px 14px; font-size: 14px; cursor: pointer;
}
.btn:hover { border-color: var(--accent); }
.btn.primary { background: var(--accent); border-color: var(--accent); color: #07101f; font-weight: 600; }
.btn.ghost { background: transparent; }
</style>
