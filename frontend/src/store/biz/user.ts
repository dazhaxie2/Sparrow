import { defineStore } from 'pinia'
import { ref } from 'vue'
import type { Profile } from '../../types/biz'
import { fetchMe } from '../../api/biz/user'
import { login as apiLogin, register as apiRegister } from '../../api/biz/user'

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
  }

  const isLoggedIn = () => !!token.value

  return { profile, token, loadProfile, login, register, logout, isLoggedIn }
})
