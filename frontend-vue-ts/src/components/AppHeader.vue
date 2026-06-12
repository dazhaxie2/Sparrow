<template>
  <header class="topbar">
    <div class="brand" @click="$router.push('/')">🐦 Sparrow <span class="sub">人类科技树</span></div>
    <div class="actions">
      <button v-if="user.profile && !user.profile.member" class="btn gold" @click="showMember = true">👑 开通会员</button>
      <span v-if="user.profile" class="user-info">
        {{ user.profile.username }}
        <span v-if="user.profile.member" class="member-badge">👑会员</span>
      </span>
      <button v-if="!user.profile" class="btn" @click="showLogin = true">登录 / 注册</button>
      <button v-if="user.profile" class="btn ghost" @click="user.logout()">退出</button>
    </div>
  </header>

  <LoginModal v-if="showLogin" @close="showLogin = false" />
  <MemberModal v-if="showMember" @close="showMember = false" />
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { useUserStore } from '../stores/user'
import LoginModal from './LoginModal.vue'
import MemberModal from './MemberModal.vue'

const user = useUserStore()
const showLogin = ref(false)
const showMember = ref(false)
</script>

<style scoped>
.topbar {
  height: 56px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 20px;
  background: var(--bg-2);
  border-bottom: 1px solid var(--line);
}
.brand {
  font-size: 18px;
  font-weight: 700;
  cursor: pointer;
}
.brand .sub {
  font-size: 13px;
  color: var(--ink-2);
  margin-left: 8px;
  font-weight: 400;
}
.actions {
  display: flex;
  align-items: center;
  gap: 10px;
}
.user-info {
  font-size: 14px;
  color: var(--ink-2);
}
.member-badge {
  color: var(--gold);
  margin-left: 4px;
}
.btn {
  background: #1d2a44;
  color: var(--ink);
  border: 1px solid var(--line);
  border-radius: 8px;
  padding: 7px 14px;
  font-size: 14px;
  cursor: pointer;
}
.btn:hover {
  border-color: var(--accent);
}
.btn.ghost {
  background: transparent;
}
.btn.gold {
  background: rgba(246, 194, 68, 0.12);
  border-color: var(--gold);
  color: var(--gold);
}
</style>
