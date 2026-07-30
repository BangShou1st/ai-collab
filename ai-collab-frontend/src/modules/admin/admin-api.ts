import { httpClient } from '../../api/http-client'
import { apiResultFromResponse } from '../../api/api-result'
import type { ApiResponse, ApiResult } from '../../api/types'
import type {
  AdminUser,
  ModelAssignment,
  ModelConfiguration,
  ModelConfigurationInput,
  ModelPurpose,
} from './types'

export const adminApi = {
  async models(): Promise<ApiResult<ModelConfiguration[]>> {
    return apiResultFromResponse(
      await httpClient.get<ApiResponse<ModelConfiguration[]>>('/admin/models'),
    )
  },
  async createModel(input: ModelConfigurationInput): Promise<ApiResult<ModelConfiguration>> {
    return apiResultFromResponse(
      await httpClient.post<ApiResponse<ModelConfiguration>>('/admin/models', input),
    )
  },
  async updateModel(
    id: string,
    input: ModelConfigurationInput,
  ): Promise<ApiResult<ModelConfiguration>> {
    return apiResultFromResponse(
      await httpClient.put<ApiResponse<ModelConfiguration>>(`/admin/models/${id}`, input),
    )
  },
  async removeModel(id: string): Promise<void> {
    await httpClient.delete(`/admin/models/${id}`)
  },
  async testModel(id: string): Promise<void> {
    await httpClient.post(`/admin/models/${id}/test`)
  },
  async assignments(): Promise<ApiResult<ModelAssignment[]>> {
    return apiResultFromResponse(
      await httpClient.get<ApiResponse<ModelAssignment[]>>('/admin/models/assignments'),
    )
  },
  async assign(purpose: ModelPurpose, configurationId: string): Promise<void> {
    await httpClient.put(`/admin/models/assignments/${purpose}`, { configurationId })
  },
  async users(): Promise<ApiResult<AdminUser[]>> {
    return apiResultFromResponse(
      await httpClient.get<ApiResponse<AdminUser[]>>('/admin/users'),
    )
  },
  async createUser(input: {
    username: string
    displayName: string
    password: string
  }): Promise<void> {
    await httpClient.post('/admin/test-users', input)
  },
  async setUserEnabled(id: string, enabled: boolean): Promise<void> {
    await httpClient.post(`/admin/users/${id}/${enabled ? 'enable' : 'disable'}`)
  },
}
