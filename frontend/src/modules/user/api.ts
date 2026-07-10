import { get, post } from '../../shared/api/request'
import type { AuthResult, Profile } from './types'

export function register(username: string, password: string) {
  return post<AuthResult>('/api/user/register', { username, password })
}

export function login(username: string, password: string) {
  return post<AuthResult>('/api/user/login', { username, password })
}

/** 发送邮箱验证码。 */
export function sendEmailCode(email: string) {
  return post<{ ok: boolean }>('/api/user/send-email-code', { email })
}

/** 邮箱验证码登录(邮箱未注册时自动注册)。 */
export function loginByEmail(email: string, code: string) {
  return post<AuthResult>('/api/user/login-by-email', { email, code })
}

export function fetchMe() {
  return get<Profile>('/api/user/me')
}
