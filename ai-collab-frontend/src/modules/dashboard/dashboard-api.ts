import { httpClient } from '../../api/http-client'
import { apiResultFromResponse } from '../../api/api-result'
import type { ApiResponse, ApiResult } from '../../api/types'
import type { DashboardView } from './types'

export const dashboardApi = {
  async get(projectId: string): Promise<ApiResult<DashboardView>> {
    return apiResultFromResponse(
      await httpClient.get<ApiResponse<DashboardView>>(`/projects/${projectId}/dashboard`),
    )
  },
}
