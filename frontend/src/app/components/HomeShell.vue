<template>
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
  <!-- AI 侧栏常驻组件:也由壳层装配,通过 chrome.aiContextNode 接收图谱上下文。 -->
  <aside v-if="!ui.graphFullScreen" class="ai-rail" :class="{ collapsed: ui.aiRailCollapsed }">
    <AiChatPanel surface="graph-rail" :context-node="chrome.aiContextNode.value" :collapsed="ui.aiRailCollapsed" @toggle="ui.toggleAiRail()" />
  </aside>
</template>

<script setup lang="ts">
import { provide, ref } from 'vue'
import LoginModal from '../../modules/user/components/LoginModal.vue'
import MemberModal from '../../modules/trade/components/MemberModal.vue'
import BindEmailModal from '../../modules/user/components/BindEmailModal.vue'
import SetPasswordModal from '../../modules/user/components/SetPasswordModal.vue'
import AiChatPanel from '../../modules/ai/components/AiChatPanel.vue'
import { useUiStore } from '../../shared/store/ui'
import { HOME_CHROME_KEY, type HomeChrome } from '../../shared/store/home-chrome'

const ui = useUiStore()

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
