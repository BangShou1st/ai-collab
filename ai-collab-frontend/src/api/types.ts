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
  systemAdmin: boolean
  emailVerified?: boolean
}

export interface AccountEmailDispatchResult {
  status: 'QUEUED' | 'COOLDOWN' | 'UNAVAILABLE' | 'NOT_REQUESTED'
  retryAfterSeconds: number
}

export interface CurrentUserProfileUpdateResult {
  user: CurrentUser
  emailChanged: boolean
  emailDispatch: AccountEmailDispatchResult
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
