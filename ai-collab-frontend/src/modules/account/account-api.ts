import { apiResultFromResponse } from '../../api/api-result'
import { httpClient } from '../../api/http-client'
import type { ApiResponse, ApiResult, CurrentUser } from '../../api/types'

export const accountApi = {
  async updateProfile(payload: { displayName: string; email: string | null }): Promise<ApiResult<CurrentUser>> {
    return apiResultFromResponse(
      await httpClient.patch<ApiResponse<CurrentUser>>('/users/me', payload),
    )
  },
}
