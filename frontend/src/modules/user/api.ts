import { get, post } from '../../shared/api/request'
import type { AuthResult, Profile } from './types'

export function login(identifier: string, password: string) {
  return post<AuthResult>('/api/user/login', { identifier, password })
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

export function sendBindEmailCode(email: string) {
  return post<{ ok: boolean }>('/api/user/email/bind/code', { email })
}

export function bindEmail(email: string, code: string) {
  return post<Profile>('/api/user/email/bind', { email, code })
}
