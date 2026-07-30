import { httpClient } from '../../api/http-client'
import { apiResultFromResponse } from '../../api/api-result'
import type { ApiResponse, ApiResult } from '../../api/types'
import type { Notification } from './types'

export const notificationApi = {
  async list(page = 0, size = 20): Promise<ApiResult<Notification[]>> {
    return apiResultFromResponse(
      await httpClient.get<ApiResponse<Notification[]>>('/notifications', { params: { page, size } }),
    )
  },
  async unreadCount(): Promise<ApiResult<{ count: number }>> {
    return apiResultFromResponse(
      await httpClient.get<ApiResponse<{ count: number }>>('/notifications/unread-count'),
    )
  },
  async listByProject(projectId: string, page = 0, size = 20): Promise<ApiResult<Notification[]>> {
    return apiResultFromResponse(
      await httpClient.get<ApiResponse<Notification[]>>(
        `/notifications/project/${projectId}`,
        { params: { page, size } },
      ),
    )
  },
  async markAsRead(notificationId: string): Promise<void> {
    await httpClient.patch(`/notifications/${notificationId}/read`)
  },
  async markAllAsRead(): Promise<void> {
    await httpClient.patch('/notifications/read-all')
  },
}
