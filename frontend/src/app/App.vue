<template>
  <AppHeader
    :graph-mode="ui.graphMode"
    :show-ai-toggle="route.path === '/' && !ui.graphFullScreen"
    :ai-rail-collapsed="ui.aiRailCollapsed"
    @show-graph="goGraph"
    @toggle-ai="ui.toggleAiRail()"
    @open-login="openIntent('login')"
    @open-member="openIntent('member')"
    @open-learning="openIntent('learning')"
    @open-settings="openIntent('settings')"
    @open-bind-email="openIntent('bind-email')"
    @open-set-password="openIntent('set-password')"
  />
  <main class="app-content">
    <router-view v-slot="{ Component }">
      <HomeShell>
        <keep-alive>
          <component :is="Component" />
        </keep-alive>
      </HomeShell>
    </router-view>
  </main>
</template>

<script setup lang="ts">
import { provide } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import AppHeader from './components/AppHeader.vue'
import HomeShell from './components/HomeShell.vue'
import { useUiStore } from '../shared/store/ui'
import { USER_STORE_KEY } from '../shared/store/user-state'
import { useUserStore } from '../modules/user/store'

const ui = useUiStore()
const route = useRoute()
const router = useRouter()

// 在根组件 provide 用户认证状态,供各业务模块 inject 取用,
// 避免 business module 之间互相 import user/store。
provide(USER_STORE_KEY, useUserStore())

/** 图谱导航:回到首页地图视图(AppHeader 在非首页时自身已会 router.push('/'))。 */
function goGraph() {
  if (route.path !== '/') {
    void router.push('/')
  } else {
    ui.graphMode = 'map'
  }
}

/**
 * 把"打开弹窗/模式"意图统一编码进 ?open= 查询参数。
 * HomeView 通过 consumeHeaderIntent 消费,保证从任意路由点击 header 都能回到首页打开对应弹窗。
 * 这与 AppHeader.activate() 对非首页的既有路由策略一致,避免在壳层重复维护弹窗状态。
 */
function openIntent(action: string) {
  if (route.path !== '/') {
    void router.push({ path: '/', query: { open: action } })
  } else {
    void router.replace({ path: '/', query: { ...route.query, open: action } })
  }
}
</script>
