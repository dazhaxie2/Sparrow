export interface Profile {
  id: number
  username: string
  email: string | null
  role: 'user' | 'admin'
  member: boolean
  memberExpireAt: string | null
  passwordSet: boolean
}

export interface AuthResult {
  token: string
}
