import { httpClient } from '../../api/http-client'
import { apiResultFromResponse } from '../../api/api-result'
import type { ApiResponse, ApiResult } from '../../api/types'
import type { Project, ProjectMember } from './types'

export const projectApi = {
  async list(): Promise<ApiResult<Project[]>> {
    return apiResultFromResponse(await httpClient.get<ApiResponse<Project[]>>('/projects'))
  },
  async get(projectId: string): Promise<ApiResult<Project>> {
    return apiResultFromResponse(await httpClient.get<ApiResponse<Project>>(`/projects/${projectId}`))
  },
  async create(payload: { name: string; description: string }): Promise<ApiResult<Project>> {
    return apiResultFromResponse(await httpClient.post<ApiResponse<Project>>('/projects', payload))
  },
  async members(projectId: string): Promise<ApiResult<ProjectMember[]>> {
    return apiResultFromResponse(
      await httpClient.get<ApiResponse<ProjectMember[]>>(`/projects/${projectId}/members`),
    )
  },
}
