import { httpClient } from './http-client'
import { apiResultFromResponse } from './api-result'
import type { ApiResponse, ApiResult } from './types'
import type { AiProviderType, DiscoveredModelMetadata, UserAiProviderPayload } from '../modules/ai/user-ai-api'

export type { DiscoveredModelMetadata }

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
  modelProfileId?: string | null
  contextWindowTokens?: number | null
  contextWindowSource?: string | null
  contextWindowVerified?: boolean | null
}

export interface AdminModelDiscoveryPayload {
  providerType: AiProviderType
  baseUrl: string
  apiPath: string
  apiKey: string
}

export type ModelProfileSource = 'UNVERIFIED' | 'PROVIDER_DOCUMENT' | 'ADMIN_VERIFIED' | 'LOCAL_FILE'

export interface AdminModelProfile {
  id: string
  providerType: 'OPENAI_COMPATIBLE' | 'ANTHROPIC' | 'GEMINI' | 'LOCAL_CUSTOM'
  baseUrl: string
  apiPath: string
  modelName: string
  contextWindowTokens: number
  maxOutputTokens: number | null
  supportsUsage: boolean
  tokenizerKind: string | null
  pricingVersion: string | null
  source: ModelProfileSource
  verified: boolean
  verifiedAt: string | null
  verifiedBy: string | null
  updatedAt: string
  modelKey: string | null
  displayName: string | null
}

export interface AdminModelProfilePayload {
  providerType: 'OPENAI_COMPATIBLE' | 'ANTHROPIC' | 'GEMINI' | 'LOCAL_CUSTOM'
  baseUrl: string
  apiPath: string
  modelName: string
  contextWindowTokens: number
  maxOutputTokens: number | null
  supportsUsage: boolean
  tokenizerKind?: string | null
  pricingVersion?: string | null
  source: ModelProfileSource
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

  async modelMetadata(id: string, refresh = false): Promise<ApiResult<DiscoveredModelMetadata[]>> {
    const res = await httpClient.get<ApiResponse<DiscoveredModelMetadata[]>>(`/admin/ai/providers/${id}/model-metadata`, {
      params: { refresh },
    })
    return apiResultFromResponse(res)
  },

  async discoverModelMetadata(payload: AdminModelDiscoveryPayload): Promise<ApiResult<DiscoveredModelMetadata[]>> {
    const res = await httpClient.post<ApiResponse<DiscoveredModelMetadata[]>>('/admin/ai/providers/model-metadata-discovery', payload)
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

export const adminModelProfileApi = {
  async list(): Promise<ApiResult<AdminModelProfile[]>> {
    const res = await httpClient.get<ApiResponse<AdminModelProfile[]>>('/admin/ai/model-profiles')
    return apiResultFromResponse(res)
  },

  async upsert(payload: AdminModelProfilePayload): Promise<ApiResult<AdminModelProfile>> {
    const res = await httpClient.put<ApiResponse<AdminModelProfile>>('/admin/ai/model-profiles/upsert', payload)
    return apiResultFromResponse(res)
  },

  async remove(id: string): Promise<ApiResult<void>> {
    const res = await httpClient.delete<ApiResponse<void>>(`/admin/ai/model-profiles/${id}`)
    return apiResultFromResponse(res)
  },
}
