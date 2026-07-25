import axios from 'axios'
import { apiResultFromResponse } from './api-result'
import type {
  AccessTokenResult, ApiResponse, ApiResult, CurrentUser, LoginResult, RegistrationPolicy,
} from './types'

const authClient = axios.create({
  baseURL: '/api/v1/auth',
  withCredentials: true,
})

export const authApi = {
  async login(username: string, password: string): Promise<ApiResult<LoginResult>> {
    const response = await authClient.post<ApiResponse<LoginResult>>('/login', { username, password })
    return apiResultFromResponse(response)
  },

  async registrationPolicy(): Promise<ApiResult<RegistrationPolicy>> {
    const response = await authClient.get<ApiResponse<RegistrationPolicy>>('/registration-policy')
    return apiResultFromResponse(response)
  },

  async register(payload: {
    username: string
    password: string
    displayName: string
    email: string | null
  }): Promise<ApiResult<LoginResult>> {
    const response = await authClient.post<ApiResponse<LoginResult>>('/register', payload)
    return apiResultFromResponse(response)
  },

  async refresh(): Promise<ApiResult<AccessTokenResult>> {
    const response = await authClient.post<ApiResponse<AccessTokenResult>>('/refresh')
    return apiResultFromResponse(response)
  },

  async me(accessToken: string): Promise<ApiResult<CurrentUser>> {
    const response = await authClient.get<ApiResponse<CurrentUser>>('/me', {
      headers: { Authorization: `Bearer ${accessToken}` },
    })
    return apiResultFromResponse(response)
  },

  async logout(): Promise<ApiResult<null>> {
    const response = await authClient.post<ApiResponse<null>>('/logout')
    return apiResultFromResponse(response)
  },

  async changePassword(
    accessToken: string,
    currentPassword: string,
    newPassword: string,
  ): Promise<ApiResult<null>> {
    const response = await authClient.post<ApiResponse<null>>(
      '/change-password',
      { currentPassword, newPassword },
      { headers: { Authorization: `Bearer ${accessToken}` } },
    )
    return apiResultFromResponse(response)
  },

  async logoutAll(accessToken: string): Promise<ApiResult<null>> {
    const response = await authClient.post<ApiResponse<null>>(
      '/logout-all',
      {},
      { headers: { Authorization: `Bearer ${accessToken}` } },
    )
    return apiResultFromResponse(response)
  },
}
