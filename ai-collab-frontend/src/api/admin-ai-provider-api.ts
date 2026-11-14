import { httpClient } from './http-client'
import { apiResultFromResponse } from './api-result'
import type { ApiResponse, ApiResult } from './types'
import type { AiProviderType, UserAiProviderPayload } from '../modules/ai/user-ai-api'

export interface AdminSharedAiProvider {
  id: string
  name: string
  providerType: 'OPENAI_COMPATIBLE' | 'ANTHROPIC' | 'GEMINI'
  baseUrl: string
  apiPath: string
  modelName: string
  enabled: boolean
  temperature: number
  maxOutputTokens: number
  capabilities: string[]
  hasApiKey: boolean
  isShared: boolean
  sharedStatus: 'UNPUBLISHED' | 'PUBLISHED' | 'REVOKED'
  sharedPublishedAt: string | null
  createdAt: string
  updatedAt: string
  capabilityConfigVersion: number
}

export interface AdminModelDiscoveryPayload {
  providerType: AiProviderType
  baseUrl: string
  apiPath: string
  apiKey: string
}

export const adminAiProviderApi = {
  async list(): Promise<ApiResult<AdminSharedAiProvider[]>> {
    const res = await httpClient.get<ApiResponse<AdminSharedAiProvider[]>>('/admin/ai/providers')
    return apiResultFromResponse(res)
  },

  async create(data: UserAiProviderPayload): Promise<ApiResult<AdminSharedAiProvider>> {
    const res = await httpClient.post<ApiResponse<AdminSharedAiProvider>>('/admin/ai/providers', data)
    return apiResultFromResponse(res)
  },

  async update(id: string, data: UserAiProviderPayload): Promise<ApiResult<AdminSharedAiProvider>> {
    const res = await httpClient.put<ApiResponse<AdminSharedAiProvider>>(`/admin/ai/providers/${id}`, data)
    return apiResultFromResponse(res)
  },

  async delete(id: string): Promise<ApiResult<void>> {
    const res = await httpClient.delete<ApiResponse<void>>(`/admin/ai/providers/${id}`)
    return apiResultFromResponse(res)
  },

  async test(id: string): Promise<ApiResult<Record<string, unknown>>> {
    const res = await httpClient.post<ApiResponse<Record<string, unknown>>>(`/admin/ai/providers/${id}/test`)
    return apiResultFromResponse(res)
  },

  async models(id: string, refresh = false): Promise<ApiResult<string[]>> {
    const res = await httpClient.get<ApiResponse<string[]>>(`/admin/ai/providers/${id}/models`, {
      params: { refresh },
    })
    return apiResultFromResponse(res)
  },

  async discoverModels(payload: AdminModelDiscoveryPayload): Promise<ApiResult<string[]>> {
    const res = await httpClient.post<ApiResponse<string[]>>('/admin/ai/providers/model-discovery', payload)
    return apiResultFromResponse(res)
  },

  async publish(id: string): Promise<ApiResult<AdminSharedAiProvider>> {
    const res = await httpClient.post<ApiResponse<AdminSharedAiProvider>>(`/admin/ai/providers/${id}/publish`)
    return apiResultFromResponse(res)
  },

  async revoke(id: string): Promise<ApiResult<AdminSharedAiProvider>> {
    const res = await httpClient.post<ApiResponse<AdminSharedAiProvider>>(`/admin/ai/providers/${id}/revoke`)
    return apiResultFromResponse(res)
  },
}
