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
  aiConfigSummary: {
    /** 是否保存了至少一个模型配置；不代表可调用 */
    configured: boolean
    /** 已启用默认模型的名称；没有或已停用时为 null */
    defaultModel: string | null
    providerCount: number
    /** 知识问答/规划/Agent 中是否至少一个用途可解析到已启用模型；不代表调用成功或健康 */
    anyPurposeUsable: boolean
  }
  recentActivity: HomeActivity[]
}

export const homeApi = {
  async get(): Promise<ApiResult<HomeData>> {
    return apiResultFromResponse(await httpClient.get<ApiResponse<HomeData>>('/home'))
  },
}
