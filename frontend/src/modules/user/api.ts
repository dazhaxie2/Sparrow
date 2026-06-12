import { get, post } from '../../shared/api/request'
import type { AuthResult, Profile } from './types'

export function register(username: string, password: string) {
  return post<AuthResult>('/api/user/register', { username, password })
}

export function login(username: string, password: string) {
  return post<AuthResult>('/api/user/login', { username, password })
}

export function fetchMe() {
  return get<Profile>('/api/user/me')
}
