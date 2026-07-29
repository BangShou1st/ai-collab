import { httpClient } from '../../api/http-client'
import { apiResultFromResponse } from '../../api/api-result'
import type { ApiResponse, ApiResult } from '../../api/types'
import type { AuditLogPage } from './types'

export const auditApi = {
  async page(
    projectId: string,
    page: number,
    size: number,
  ): Promise<ApiResult<AuditLogPage>> {
    return apiResultFromResponse(
      await httpClient.get<ApiResponse<AuditLogPage>>(
        `/projects/${projectId}/audit-logs`,
        { params: { page, size } },
      ),
    )
  },
}
