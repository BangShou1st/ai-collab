import { httpClient } from '../../api/http-client'
import { apiResultFromResponse } from '../../api/api-result'
import type { ApiResponse, ApiResult } from '../../api/types'

export interface SystemEmbeddingConfig {
  provider: string
  baseUrl: string
  apiPath: string
  hasApiKey: boolean
  modelName: string
  dimensions: number
  batchSize: number
  enabled: boolean
  fingerprint: string | null
  staleChunks: number
}

export interface SystemEmbeddingPayload {
  provider: string
  baseUrl: string
  apiPath: string
  apiKey: string | null
  modelName: string
  dimensions: number
  batchSize: number
}

export const embeddingAdminApi = {
  async get(): Promise<ApiResult<SystemEmbeddingConfig>> {
    return apiResultFromResponse(await httpClient.get<ApiResponse<SystemEmbeddingConfig>>('/admin/embedding-config'))
  },
  async update(payload: SystemEmbeddingPayload): Promise<ApiResult<SystemEmbeddingConfig>> {
    return apiResultFromResponse(await httpClient.put<ApiResponse<SystemEmbeddingConfig>>('/admin/embedding-config', payload))
  },
  async test(): Promise<void> {
    await httpClient.post('/admin/embedding-config/test', {})
  },
  async reindex(payload: SystemEmbeddingPayload): Promise<ApiResult<{ documents: number }>> {
    return apiResultFromResponse(await httpClient.post<ApiResponse<{ documents: number }>>('/admin/embedding-config/reindex', payload))
  },
}

