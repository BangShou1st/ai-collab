export interface ApiResponse<T> {
  code: string
  message: string
  data: T
}

export interface ApiResult<T> {
  httpStatus: number | null
  code: string
  message: string
  data: T
}

export interface CurrentUser {
  id: string
  username: string
  displayName: string
  email: string | null
  status: 'ACTIVE' | 'DISABLED'
}

export interface AccessTokenResult {
  accessToken: string
  tokenType: string
  expiresInSeconds: number
}

export interface LoginResult extends AccessTokenResult {
  user: CurrentUser
}

export interface RegistrationPolicy {
  enabled: boolean
}
