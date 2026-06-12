import { post, get } from './http'

export interface AuthResult {
  token: string
}

export interface Profile {
  id: number
  username: string
  member: boolean
  memberExpireAt: string | null
}

export function register(username: string, password: string) {
  return post<AuthResult>('/api/user/register', { username, password })
}

export function login(username: string, password: string) {
  return post<AuthResult>('/api/user/login', { username, password })
}

export function fetchMe() {
  return get<Profile>('/api/user/me')
}
