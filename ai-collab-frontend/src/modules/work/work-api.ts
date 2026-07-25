import { httpClient } from '../../api/http-client'
import { apiResultFromResponse } from '../../api/api-result'
import type { ApiResponse, ApiResult } from '../../api/types'
import type { Milestone, Task, TaskComment, TaskStatus } from './types'

export const workApi = {
  async milestones(projectId: string): Promise<ApiResult<Milestone[]>> {
    return apiResultFromResponse(
      await httpClient.get<ApiResponse<Milestone[]>>(`/projects/${projectId}/milestones`),
    )
  },
  async createMilestone(projectId: string, payload: object): Promise<ApiResult<Milestone>> {
    return apiResultFromResponse(
      await httpClient.post<ApiResponse<Milestone>>(`/projects/${projectId}/milestones`, payload),
    )
  },
  async updateMilestone(projectId: string, milestoneId: string, payload: object): Promise<ApiResult<Milestone>> {
    return apiResultFromResponse(
      await httpClient.patch<ApiResponse<Milestone>>(
        `/projects/${projectId}/milestones/${milestoneId}`, payload,
      ),
    )
  },
  async deleteMilestone(projectId: string, milestoneId: string): Promise<void> {
    await httpClient.delete(`/projects/${projectId}/milestones/${milestoneId}`)
  },
  async tasks(projectId: string, params: Record<string, string | undefined> = {}): Promise<ApiResult<Task[]>> {
    return apiResultFromResponse(
      await httpClient.get<ApiResponse<Task[]>>(`/projects/${projectId}/tasks`, { params }),
    )
  },
  async task(projectId: string, taskId: string): Promise<ApiResult<Task>> {
    return apiResultFromResponse(
      await httpClient.get<ApiResponse<Task>>(`/projects/${projectId}/tasks/${taskId}`),
    )
  },
  async createTask(projectId: string, payload: object): Promise<ApiResult<Task>> {
    return apiResultFromResponse(
      await httpClient.post<ApiResponse<Task>>(`/projects/${projectId}/tasks`, payload),
    )
  },
  async updateTask(projectId: string, taskId: string, payload: object): Promise<ApiResult<Task>> {
    return apiResultFromResponse(
      await httpClient.patch<ApiResponse<Task>>(`/projects/${projectId}/tasks/${taskId}`, payload),
    )
  },
  async updateStatus(projectId: string, task: Task, status: TaskStatus): Promise<ApiResult<Task>> {
    return this.updateTask(projectId, task.id, { status, version: task.version })
  },
  async replaceDependencies(projectId: string, taskId: string, dependencyIds: string[]): Promise<ApiResult<Task>> {
    return apiResultFromResponse(
      await httpClient.put<ApiResponse<Task>>(
        `/projects/${projectId}/tasks/${taskId}/dependencies`, { dependencyIds },
      ),
    )
  },
  async comments(projectId: string, taskId: string): Promise<ApiResult<TaskComment[]>> {
    return apiResultFromResponse(
      await httpClient.get<ApiResponse<TaskComment[]>>(
        `/projects/${projectId}/tasks/${taskId}/comments`,
      ),
    )
  },
  async createComment(projectId: string, taskId: string, content: string): Promise<ApiResult<TaskComment>> {
    return apiResultFromResponse(
      await httpClient.post<ApiResponse<TaskComment>>(
        `/projects/${projectId}/tasks/${taskId}/comments`, { content },
      ),
    )
  },
}
