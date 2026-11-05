import { apiResultFromResponse } from '../../api/api-result'
import { httpClient } from '../../api/http-client'
import type { ApiResponse, ApiResult, CurrentUserProfileUpdateResult } from '../../api/types'

export const accountApi = {
  async updateProfile(payload: { displayName: string; email: string | null }): Promise<ApiResult<CurrentUserProfileUpdateResult>> {
    return apiResultFromResponse(
      await httpClient.patch<ApiResponse<CurrentUserProfileUpdateResult>>('/users/me', payload),
    )
  },
}
