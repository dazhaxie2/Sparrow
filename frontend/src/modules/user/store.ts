import { defineStore } from 'pinia'
import { ref } from 'vue'
import type { Profile } from './types'
import { fetchMe } from './api'
import { bindEmail as apiBindEmail, login as apiLogin, register as apiRegister, loginByEmail as apiLoginByEmail } from './api'
import { useChatStore } from '../ai/store/chat'

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

  async function register(username: string, password: string) {
    const res = await apiRegister(username, password)
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
    useChatStore().reset()
  }

  const isLoggedIn = () => !!token.value
  const isAdmin = () => profile.value?.role === 'admin'

  return { profile, token, loadProfile, login, register, loginByEmail, bindEmail, logout, isLoggedIn, isAdmin }
})
