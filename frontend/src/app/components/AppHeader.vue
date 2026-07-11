<template>
  <header class="topbar">
    <button class="brand" type="button" @click="$router.push('/')">
      <span class="brand-copy">
        <strong>SPARROW</strong>
        <span>Human Technology Tree</span>
      </span>
    </button>

    <nav class="top-nav" aria-label="Primary">
      <button class="nav-item" :class="{ active: $route.path === '/' && graphMode === 'map' }" type="button" @click="activate('graph')">图谱</button>
      <button class="nav-item" :class="{ active: $route.path.startsWith('/chains') }" type="button" @click="goChains">产业链</button>
      <router-link v-if="user.isAdmin()" class="nav-item" :class="{ active: $route.path === '/admin/agents' }" to="/admin/agents">Agent 配置</router-link>
      <router-link v-if="user.isAdmin()" class="nav-item" :class="{ active: $route.path === '/admin/models' }" to="/admin/models">模型</router-link>
      <button class="nav-item" type="button" @click="activate('member')">会员</button>
    </nav>

    <div class="actions">
      <button
        v-if="showAiToggle"
        class="btn ai-toggle"
        :class="{ active: !aiRailCollapsed }"
        type="button"
        :title="aiRailCollapsed ? '展开 AI 对话' : '收起 AI 对话'"
        @click="emit('toggleAi')"
      >
        <Bot :size="14" />
        <span>{{ aiRailCollapsed ? 'AI' : 'AI 对话' }}</span>
      </button>
      <button v-if="!user.profile" class="btn primary" type="button" @click="activate('login')">
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
          <button v-if="!user.profile?.email" type="button" @click="openBindEmail"><Mail :size="15" />绑定邮箱</button>
          <button type="button" @click="select('openLearning')"><GraduationCap :size="15" />我的学习</button>
          <button type="button" @click="select('openMember')"><Crown :size="15" />{{ user.profile.member ? '会员续期' : '开通会员' }}</button>
          <button type="button" @click="select('openSettings')"><Settings :size="15" />设置</button>
          <button type="button" @click="emit('openSetPassword')"><KeyRound :size="15" />{{ user.profile?.passwordSet ? '修改密码' : '设置密码' }}</button>
          <div class="menu-sep"></div>
          <button type="button" class="danger" @click="logout"><LogOut :size="15" />退出登录</button>
        </div>
      </div>
    </div>
  </header>
</template>

<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Bot, ChevronDown, Crown, GraduationCap, KeyRound, LogOut, Mail, Settings } from '@lucide/vue'
import { useUserStore } from '../../modules/user/store'

const user = useUserStore()
const route = useRoute()
const router = useRouter()
withDefaults(defineProps<{ graphMode?: 'map' | 'dialog'; showAiToggle?: boolean; aiRailCollapsed?: boolean }>(), {
  graphMode: 'map',
  showAiToggle: false,
  aiRailCollapsed: true,
})
const emit = defineEmits<{
  showGraph: []
  toggleAi: []
  openLogin: []
  openMember: []
  openLearning: []
  openSettings: []
  openBindEmail: []
  openSetPassword: []
}>()
const menuOpen = ref(false)

type HeaderAction = 'graph' | 'login' | 'member' | 'learning' | 'settings'

function activate(action: HeaderAction) {
  // Header 会被多个独立路由复用。首页可直接操作当前组件状态；其他页面则携带
  // 一次性意图返回首页，由 HomeView 打开对应模式/弹窗，避免事件无人监听。
  if (route.path !== '/') {
    if (action === 'graph') void router.push('/')
    else void router.push({ path: '/', query: { open: action } })
    return
  }

  if (action === 'graph') emit('showGraph')
  else if (action === 'login') emit('openLogin')
  else if (action === 'member') emit('openMember')
  else if (action === 'learning') emit('openLearning')
  else emit('openSettings')
}

function select(action: 'openLearning' | 'openMember' | 'openSettings') {
  menuOpen.value = false
  if (action === 'openLearning') activate('learning')
  else if (action === 'openMember') activate('member')
  else activate('settings')
}

/**
 * 产业链导航:记忆最后访问的工作台。会话内只要进过某个 /chains/:id,
 * 点"产业链"就回到那个工作台;从没进过任何工作台时才回列表页 /chains。
 * 工作台页内有自己的"← 产业链"返回按钮可回到列表页切换其它工作台。
 */
function goChains() {
  const lastId = sessionStorage.getItem('sparrow_last_chain_id')
  void router.push(lastId ? `/chains/${lastId}` : '/chains')
}

function logout() {
  menuOpen.value = false
  user.logout()
}

function openBindEmail() {
  menuOpen.value = false
  if (route.path !== '/') {
    void router.push({ path: '/', query: { open: 'bind-email' } })
  } else {
    emit('openBindEmail')
  }
}

function closeMenu(event: MouseEvent) {
  if (!(event.target as HTMLElement).closest('.user-menu')) menuOpen.value = false
}

onMounted(() => document.addEventListener('click', closeMenu))
onUnmounted(() => document.removeEventListener('click', closeMenu))
</script>

<style scoped>
.topbar {
  position: relative;
  z-index: 100;
  height: 52px;
  display: grid;
  grid-template-columns: minmax(150px, 1fr) auto minmax(150px, 1fr);
  align-items: center;
  gap: 14px;
  padding: 0 18px;
  background: rgba(255, 255, 255, 0.97);
  border-bottom: 1px solid var(--line);
  box-shadow: 0 1px 8px rgba(20, 24, 29, 0.025);
}

.brand {
  display: inline-flex;
  align-items: center;
  gap: 0;
  width: fit-content;
  border: 0;
  background: transparent;
  cursor: pointer;
  text-align: left;
}

.brand-copy {
  display: grid;
  gap: 0;
}

.brand-copy strong {
  font-size: 15px;
  letter-spacing: 0.14em;
}

.brand-copy span {
  color: var(--muted);
  font-size: 8px;
  letter-spacing: 0.11em;
  text-transform: uppercase;
}

.top-nav {
  display: inline-flex;
  align-items: center;
  border: 0;
  border-radius: 8px;
  background: #f2f2f1;
  padding: 3px;
  gap: 2px;
}

.nav-item {
  border: 0;
  background: transparent;
  min-width: 54px;
  padding: 6px 12px;
  border-radius: 6px;
  color: var(--ink-2);
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.04em;
  text-transform: uppercase;
  cursor: pointer;
  transition: color 0.16s ease, background 0.16s ease;
}

/* router-link 渲染为 a 标签,补齐链接默认样式重置。 */
a.nav-item {
  display: inline-flex;
  align-items: center;
  text-decoration: none;
}

.nav-item:hover {
  color: var(--ink);
}

.nav-item.active {
  background: #fff;
  color: var(--ink);
  box-shadow: 0 1px 4px rgba(20, 24, 29, 0.08);
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
  min-height: 32px;
  border: 1px solid var(--line);
  background: var(--panel);
  color: var(--ink);
  border-radius: 7px;
  padding: 0 12px;
  font-size: 11px;
  cursor: pointer;
  transition: border-color 0.16s ease, color 0.16s ease, background 0.16s ease;
}

.btn:hover {
  border-color: var(--ink);
}

.btn.primary {
  background: #fff;
  border-color: var(--line);
  color: var(--ink-2);
}

.btn.ai-toggle {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.btn.ai-toggle.active {
  border-color: var(--accent);
  background: rgba(255, 87, 34, 0.08);
  color: var(--accent);
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
    height: 48px;
    grid-template-columns: minmax(88px, 1fr) auto minmax(70px, 1fr);
    gap: 8px;
    padding: 0 10px;
  }

  .brand-copy span {
    display: none;
  }

  .brand-copy strong {
    font-size: 12px;
  }

  .nav-item {
    min-width: 44px;
    padding: 5px 8px;
    font-size: 10px;
  }

  .btn {
    min-height: 29px;
    padding: 0 8px;
    font-size: 10px;
  }
}
</style>
