import { httpClient } from './http-client'
import { apiResultFromResponse } from './api-result'
import type { ApiResponse, ApiResult } from './types'

export interface AdminNotification {
  id: string
  category: 'AI_PROVIDER' | 'EMBEDDING' | 'ACCOUNT' | 'MAINTENANCE' | 'SYSTEM'
  level: 'INFO' | 'WARNING' | 'ERROR'
  title: string
  content: string
  targetPath?: string
  dedupeKey?: string
  isRead: boolean
  readAt?: string
  createdAt: string
}

export const adminNotificationApi = {
  async list(unreadOnly = false, limit = 50): Promise<ApiResult<AdminNotification[]>> {
    const res = await httpClient.get<ApiResponse<AdminNotification[]>>('/admin/notifications', { params: { unreadOnly, limit } })
    return apiResultFromResponse(res)
  },

  async unreadCount(): Promise<ApiResult<{ unreadCount: number }>> {
    const res = await httpClient.get<ApiResponse<{ unreadCount: number }>>('/admin/notifications/unread-count')
    return apiResultFromResponse(res)
  },

  async markAsRead(id: string): Promise<ApiResult<void>> {
    const res = await httpClient.post<ApiResponse<void>>(`/admin/notifications/${id}/read`)
    return apiResultFromResponse(res)
  },

  async markAllAsRead(): Promise<ApiResult<void>> {
    const res = await httpClient.post<ApiResponse<void>>('/admin/notifications/read-all')
    return apiResultFromResponse(res)
  },
}
