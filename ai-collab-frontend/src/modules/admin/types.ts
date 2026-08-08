export interface AdminUser {
  id: string
  username: string
  displayName: string
  email: string | null
  status: 'ACTIVE' | 'DISABLED'
  systemAdmin: boolean
  lastLoginAt: string | null
  createdAt: string
}
