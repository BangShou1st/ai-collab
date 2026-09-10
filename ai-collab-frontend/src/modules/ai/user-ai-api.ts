import { httpClient } from '../../api/http-client'
import { apiResultFromResponse } from '../../api/api-result'
import type { ApiResponse, ApiResult } from '../../api/types'

export type AiProviderType = 'OPENAI_COMPATIBLE' | 'ANTHROPIC' | 'GEMINI'
export type AiPurpose = 'KNOWLEDGE_CHAT' | 'PLANNING' | 'AGENT'

export interface UserAiProvider {
  id: string
  name: string
  presetCode: string | null
  providerType: AiProviderType
  baseUrl: string
  apiPath: string
  hasApiKey: boolean
  modelName: string
  enabled: boolean
  isDefault: boolean
  temperature: number
  maxOutputTokens: number
  capabilities: string[]
  updatedAt: string
}

export interface UserAiProviderPayload {
  name: string
  providerType: AiProviderType
  baseUrl: string
  apiPath: string
  apiKey: string | null
  modelName: string
  enabled: boolean
  temperature: number
  maxOutputTokens: number
  capabilities: string[]
}

export const userAiApi = {
  async list(): Promise<ApiResult<UserAiProvider[]>> {
    return apiResultFromResponse(await httpClient.get<ApiResponse<UserAiProvider[]>>('/user/ai-providers'))
  },
  async create(payload: UserAiProviderPayload): Promise<ApiResult<UserAiProvider>> {
    return apiResultFromResponse(await httpClient.post<ApiResponse<UserAiProvider>>('/user/ai-providers', payload))
  },
  async update(id: string, payload: UserAiProviderPayload): Promise<ApiResult<UserAiProvider>> {
    return apiResultFromResponse(await httpClient.patch<ApiResponse<UserAiProvider>>(`/user/ai-providers/${id}`, payload))
  },
  async remove(id: string): Promise<void> {
    await httpClient.delete(`/user/ai-providers/${id}`)
  },
  async test(id: string): Promise<ApiResult<unknown>> {
    return apiResultFromResponse(await httpClient.post<ApiResponse<unknown>>(`/user/ai-providers/${id}/test`, {}))
  },
  async setDefault(providerId: string): Promise<void> {
    await httpClient.put('/user/ai-providers/defaults', { providerId })
  },
  async assignPurpose(purpose: AiPurpose, providerId: string): Promise<void> {
    await httpClient.put(`/user/ai-providers/purposes/${purpose}`, { providerId })
  },
  async unassignPurpose(purpose: AiPurpose): Promise<void> {
    await httpClient.delete(`/user/ai-providers/purposes/${purpose}`)
  },
}
