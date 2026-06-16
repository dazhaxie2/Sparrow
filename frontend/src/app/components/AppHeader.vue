<template>
  <header class="topbar">
    <button class="brand" type="button" @click="$router.push('/')">
      <span class="brand-mark">SP</span>
      <span class="brand-copy">
        <strong>SPARROW</strong>
        <span>Human Technology Tree</span>
      </span>
    </button>

    <nav class="top-nav" aria-label="Primary">
      <button class="nav-item active" type="button" @click="$router.push('/')">Graph</button>
      <button class="nav-item" type="button" @click="$emit('focusAi')">AI Guide</button>
      <button class="nav-item" type="button" @click="$emit('openMember')">Membership</button>
    </nav>

    <div class="actions">
      <button v-if="!user.profile" class="btn primary" type="button" @click="$emit('openLogin')">
        登录 / 注册
      </button>
      <div v-else class="user-menu">
        <button class="user-trigger" type="button" @click.stop="menuOpen = !menuOpen">
          <span class="status-dot"></span>
          <span class="user-name">{{ user.profile.username }}</span>
          <span v-if="user.profile.member" class="member-badge">PRO</span>
          <ChevronDown :size="14" />
        </button>
        <div v-if="menuOpen" class="user-dropdown">
          <button type="button" @click="select('openLearning')"><GraduationCap :size="15" />我的学习</button>
          <button type="button" @click="select('openMember')"><Crown :size="15" />{{ user.profile.member ? '会员续期' : '开通会员' }}</button>
          <button type="button" @click="select('openSettings')"><Settings :size="15" />设置</button>
          <div class="menu-sep"></div>
          <button type="button" class="danger" @click="logout"><LogOut :size="15" />退出登录</button>
        </div>
      </div>
    </div>
  </header>
</template>

<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'
import { ChevronDown, Crown, GraduationCap, LogOut, Settings } from '@lucide/vue'
import { useUserStore } from '../../modules/user/store'

const user = useUserStore()
const emit = defineEmits<{ openLogin: []; openMember: []; focusAi: []; openLearning: []; openSettings: [] }>()
const menuOpen = ref(false)

function select(action: 'openLearning' | 'openMember' | 'openSettings') {
  menuOpen.value = false
  emit(action)
}

function logout() {
  menuOpen.value = false
  user.logout()
}

function closeMenu(event: MouseEvent) {
  if (!(event.target as HTMLElement).closest('.user-menu')) menuOpen.value = false
}

onMounted(() => document.addEventListener('click', closeMenu))
onUnmounted(() => document.removeEventListener('click', closeMenu))
</script>

<style scoped>
.topbar {
  height: 64px;
  display: grid;
  grid-template-columns: minmax(220px, 1fr) auto minmax(260px, 1fr);
  align-items: center;
  gap: 18px;
  padding: 0 22px;
  background: var(--panel);
  border-bottom: 1px solid var(--line);
}

.brand {
  display: inline-flex;
  align-items: center;
  gap: 10px;
  width: fit-content;
  border: 0;
  background: transparent;
  cursor: pointer;
  text-align: left;
}

.brand-mark {
  display: grid;
  place-items: center;
  width: 34px;
  height: 34px;
  background: var(--ink);
  color: var(--bg);
  font-size: 12px;
  font-weight: 800;
  letter-spacing: 0.08em;
}

.brand-copy {
  display: grid;
  gap: 2px;
}

.brand-copy strong {
  font-size: 16px;
  letter-spacing: 0.12em;
}

.brand-copy span {
  color: var(--ink-2);
  font-size: 11px;
  letter-spacing: 0.08em;
  text-transform: uppercase;
}

.top-nav {
  display: inline-flex;
  align-items: center;
  border: 1px solid var(--line);
  background: var(--surface);
  padding: 4px;
  gap: 4px;
}

.nav-item {
  border: 0;
  background: transparent;
  padding: 7px 13px;
  color: var(--ink-2);
  font-size: 12px;
  letter-spacing: 0.04em;
  text-transform: uppercase;
  cursor: pointer;
  transition: color 0.16s ease, background 0.16s ease;
}

.nav-item:hover {
  color: var(--ink);
}

.nav-item.active {
  background: var(--ink);
  color: var(--bg);
}

.actions {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 10px;
  min-width: 0;
}

.user-info {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  min-width: 0;
  color: var(--ink-2);
  font-size: 13px;
}

.status-dot {
  width: 7px;
  height: 7px;
  background: var(--success);
}

.member-badge {
  border: 1px solid rgba(255, 87, 34, 0.4);
  color: var(--accent);
  padding: 2px 5px;
  font-size: 10px;
  letter-spacing: 0.08em;
}

.user-menu {
  position: relative;
}

.user-trigger {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  min-height: 34px;
  border: 1px solid var(--line-strong);
  background: var(--panel);
  color: var(--ink);
  padding: 0 12px;
  font-size: 13px;
  cursor: pointer;
  transition: border-color 0.16s ease;
}

.user-trigger:hover {
  border-color: var(--ink);
}

.user-name {
  max-width: 120px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.user-dropdown {
  position: absolute;
  top: calc(100% + 6px);
  right: 0;
  z-index: 200;
  min-width: 172px;
  display: grid;
  gap: 2px;
  border: 1px solid var(--line-strong);
  background: var(--panel);
  box-shadow: var(--shadow-md);
  padding: 6px;
}

.user-dropdown button {
  display: flex;
  align-items: center;
  gap: 9px;
  width: 100%;
  min-height: 36px;
  border: 0;
  background: transparent;
  color: var(--ink);
  padding: 0 10px;
  font-size: 13px;
  text-align: left;
  cursor: pointer;
}

.user-dropdown button:hover {
  background: var(--surface);
}

.user-dropdown button svg {
  color: var(--ink-2);
}

.user-dropdown button.danger,
.user-dropdown button.danger svg {
  color: var(--danger);
}

.menu-sep {
  height: 1px;
  margin: 4px 0;
  background: var(--line);
}

.btn {
  min-height: 34px;
  border: 1px solid var(--line-strong);
  background: var(--panel);
  color: var(--ink);
  padding: 0 14px;
  font-size: 13px;
  cursor: pointer;
  transition: border-color 0.16s ease, color 0.16s ease, background 0.16s ease;
}

.btn:hover {
  border-color: var(--ink);
}

.btn.primary {
  background: var(--ink);
  border-color: var(--ink);
  color: var(--bg);
}

.btn.accent {
  border-color: var(--accent);
  color: var(--accent);
  background: rgba(255, 87, 34, 0.08);
}

.btn.ghost {
  background: transparent;
}

@media (max-width: 920px) {
  .topbar {
    grid-template-columns: 1fr auto;
  }

  .top-nav {
    display: none;
  }
}
</style>
