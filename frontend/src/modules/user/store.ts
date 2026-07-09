import { defineStore } from 'pinia'
import { ref } from 'vue'
import type { Profile } from './types'
import { fetchMe } from './api'
import { login as apiLogin, register as apiRegister } from './api'
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

  async function login(username: string, password: string) {
    const res = await apiLogin(username, password)
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

  function logout() {
    token.value = null
    profile.value = null
    localStorage.removeItem('sparrow_token')
    // 清空 AI 聊天历史:会话列表是本地缓存的前一个账号数据,不清空会残留到下次登录。
    useChatStore().reset()
  }

  const isLoggedIn = () => !!token.value

  return { profile, token, loadProfile, login, register, logout, isLoggedIn }
})
