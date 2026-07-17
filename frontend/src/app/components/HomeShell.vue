<template>
  <!-- 壳层根容器:始终渲染(随 router-view 稳定存在,不被路由切换销毁),
       这样内部 <keep-alive> 的缓存能跨路由保留,首页 sigma 图谱实例与数据不再重载。
       仅在首页(home)且有 AI 栏时启用横向 flex 布局,让业务视图与 AI 栏并排;
       非首页直接透传 slot,避免给产业链等页面强加 flex 布局。
       ⚠ .ai-rail 的布局样式必须定义在本组件:aside 由 HomeShell 渲染,放进 HomeView
       的 scoped style 会因属性选择器不匹配而失效,导致 aside 掉到页面底部。 -->
  <div class="home-shell" :class="{ 'has-rail': isHome && !ui.graphFullScreen }">
    <slot />
    <!-- 跨模块业务弹窗在 app 壳层装配,AiChatPanel 侧栏也放壳层以避免 HomeView 跨模块 import。
         开关状态由壳层持有;业务层通过 useHomeChrome() 控制器触发。
         AiChatPanel 的 context-node 通过 chrome.aiContextNode 由业务层写入。 -->
    <LoginModal v-if="chrome.showLogin.value" @close="chrome.showLogin.value = false" />
    <MemberModal v-if="chrome.showMember.value" @close="chrome.showMember.value = false" />
    <BindEmailModal v-if="chrome.showBindEmail.value" @close="chrome.showBindEmail.value = false" @bound="chrome.showBindEmail.value = false" />
    <SetPasswordModal
      v-if="chrome.showSetPassword.value"
      @done="chrome.showSetPassword.value = false; chrome.onSetPasswordDone?.()"
      @skip="chrome.showSetPassword.value = false; chrome.onSetPasswordSkip?.()"
    />
    <!-- AI 侧栏常驻组件:仅在首页且非全屏时装配(产业链工作台有自己的 AI 栏,不在此装配)。
         通过 chrome.aiContextNode 接收图谱上下文。 -->
    <aside v-if="isHome && !ui.graphFullScreen" class="ai-rail" :class="{ collapsed: ui.aiRailCollapsed }">
      <AiChatPanel surface="graph-rail" :context-node="chrome.aiContextNode.value" :collapsed="ui.aiRailCollapsed" @toggle="ui.toggleAiRail()" />
    </aside>
  </div>
</template>

<style scoped>
/* 壳层根:填满 .app-content 的剩余高度(由全局 index.css 的 calc(100vh-52px) 给定)。
   默认透传(非首页);has-rail 修饰类(首页且有 AI 栏)才启用横向 flex。 */
.home-shell {
  height: 100%;
  min-height: 0;
}
.home-shell.has-rail {
  display: flex;
}

/* 右侧常驻 AI 对话栏:宽 390px,折叠态收为 44px 竖条。
   ⚠ 此规则原本误放在 HomeView.vue 的 scoped style,但 aside 由本组件渲染,
   导致样式不生效、aside 掉到页面底部;现已归位到渲染它的组件。 */
.ai-rail {
  flex: 0 0 390px;
  min-width: 0;
  display: flex;
  flex-direction: column;
  transition: flex-basis 0.3s cubic-bezier(0.25, 0.8, 0.25, 1), background 0.2s ease;
}
/* 折叠态:容器透明融入背景,避免突兀的纯黑色块 */
.ai-rail.collapsed {
  flex: 0 0 44px;
  background: transparent;
}

@media (max-width: 920px) {
  /* 移动端隐藏常驻 AI 栏(屏宽不足,避免挤压图谱);用户改用全屏沉浸外的其它入口 */
  .ai-rail {
    display: none;
  }
}
</style>

<script setup lang="ts">
import { computed, provide, ref } from 'vue'
import { useRoute } from 'vue-router'
import LoginModal from '../../modules/user/components/LoginModal.vue'
import MemberModal from '../../modules/trade/components/MemberModal.vue'
import BindEmailModal from '../../modules/user/components/BindEmailModal.vue'
import SetPasswordModal from '../../modules/user/components/SetPasswordModal.vue'
import AiChatPanel from '../../modules/ai/components/AiChatPanel.vue'
import { useUiStore } from '../../shared/store/ui'
import { HOME_CHROME_KEY, type HomeChrome } from '../../shared/store/home-chrome'

const ui = useUiStore()
const route = useRoute()
// 仅首页需要 AI 栏 + flex 布局;产业链等页面有自己的布局壳,不在此强加 flex。
const isHome = computed(() => route.name === 'home')

const showLogin = ref(false)
const showMember = ref(false)
const showBindEmail = ref(false)
const showSetPassword = ref(false)
const aiContextNode = ref<{ id: number; name: string } | null>(null)

const chrome: HomeChrome = {
  showLogin,
  showMember,
  showBindEmail,
  showSetPassword,
  openLogin: () => { showLogin.value = true },
  openMember: () => { showMember.value = true },
  openBindEmail: () => { showBindEmail.value = true },
  openSetPassword: () => { showSetPassword.value = true },
  aiContextNode,
  onSetPasswordDone: null,
  onSetPasswordSkip: null,
}
provide(HOME_CHROME_KEY, chrome)
</script>
