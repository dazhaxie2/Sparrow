import { defineStore } from 'pinia'
import { ref } from 'vue'
import type { Profile } from './types'
import { fetchMe } from './api'
import { bindEmail as apiBindEmail, login as apiLogin, loginByEmail as apiLoginByEmail } from './api'

// useChatStore 用惰性动态 import 取用,避免 user/store 与 ai/store 在模块加载期形成
// 静态依赖环。Pinia 单例在 logout() 被调用时必然已初始化,此时动态加载安全。
async function resetChatStore() {
  const { useChatStore } = await import('../ai/store/chat')
  useChatStore().reset()
}

export const useUserStore = defineStore('user', () => {
  const profile = ref<Profile | null>(null)
  const token = ref<string | null>(localStorage.getItem('sparrow_token'))

  async function loadProfile() {
    if (!token.value) { profile.value = null; return }
    try {
      profile.value = await fetchMe()
    } catch {
      token.value = null
      localStorage.removeItem('sparrow_token')
      profile.value = null
    }
  }

  async function login(identifier: string, password: string) {
    const res = await apiLogin(identifier, password)
    token.value = res.token
    localStorage.setItem('sparrow_token', res.token)
    await loadProfile()
  }

  /** 邮箱验证码登录:先发送验证码,再用验证码登录。 */
  async function loginByEmail(email: string, code: string) {
    const res = await apiLoginByEmail(email, code)
    token.value = res.token
    localStorage.setItem('sparrow_token', res.token)
    await loadProfile()
  }

  async function bindEmail(email: string, code: string) {
    profile.value = await apiBindEmail(email, code)
  }

  function logout() {
    token.value = null
    profile.value = null
    localStorage.removeItem('sparrow_token')
    // 清空 AI 聊天历史:会话列表是本地缓存的前一个账号数据,不清空会残留到下次登录。
    // 动态 import 解开 user/store ↔ ai/store 的静态加载环;reset 是幂等清理,
    // 异步几毫秒后执行不影响登出语义(token/profile 已同步清空)。
    void resetChatStore()
  }

  const isLoggedIn = () => !!token.value
  const isAdmin = () => profile.value?.role === 'admin'

  return { profile, token, loadProfile, login, loginByEmail, bindEmail, logout, isLoggedIn, isAdmin }
})
