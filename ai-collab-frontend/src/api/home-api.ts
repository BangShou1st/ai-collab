import { httpClient } from './http-client'
import { apiResultFromResponse } from './api-result'
import type { ApiResponse, ApiResult } from './types'

export interface HomeProject {
  id: string
  name: string
  status: string
  role: string
}

export interface HomeTask {
  id: string
  projectId: string
  projectName: string
  title: string
  status: string
  priority: string
  dueDate: string | null
}

export interface HomeActivity {
  id: string
  projectId: string
  projectName: string
  action: string
  createdAt: string
}

export interface HomeData {
  recentProjects: HomeProject[]
  myTasks: HomeTask[]
  notificationsSummary: { unread: number }
  pendingApprovals: number
  aiConfigSummary: { configured: boolean; defaultModel: string | null; providerCount: number }
  recentActivity: HomeActivity[]
}

export const homeApi = {
  async get(): Promise<ApiResult<HomeData>> {
    return apiResultFromResponse(await httpClient.get<ApiResponse<HomeData>>('/home'))
  },
}
