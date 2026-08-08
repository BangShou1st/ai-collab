import { httpClient } from '../../api/http-client'
import { apiResultFromResponse } from '../../api/api-result'
import type { ApiResponse, ApiResult } from '../../api/types'
import type { AdminUser } from './types'

export const adminApi = {
  async users(): Promise<ApiResult<AdminUser[]>> {
    return apiResultFromResponse(
      await httpClient.get<ApiResponse<AdminUser[]>>('/admin/users'),
    )
  },
  async createUser(input: {
    username: string
    displayName: string
    password: string
  }): Promise<void> {
    await httpClient.post('/admin/test-users', input)
  },
  async setUserEnabled(id: string, enabled: boolean): Promise<void> {
    await httpClient.post(`/admin/users/${id}/${enabled ? 'enable' : 'disable'}`)
  },
}
