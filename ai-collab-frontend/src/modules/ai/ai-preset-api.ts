import { httpClient } from '../../api/http-client'
import { apiResultFromResponse } from '../../api/api-result'
import type { ApiResponse, ApiResult } from '../../api/types'

export interface PresetStatus { code: string; displayName: string; connected: boolean; modelName: string | null; enabled: boolean; isDefault: boolean; hasApiKey: boolean }

export const aiPresetApi = {
  async presets(): Promise<ApiResult<PresetStatus[]>> {
    return apiResultFromResponse(await httpClient.get<ApiResponse<PresetStatus[]>>('/user/ai-provider-presets'))
  },
  async models(refresh = false): Promise<ApiResult<string[]>> {
    return apiResultFromResponse(await httpClient.get<ApiResponse<string[]>>(`/user/ai-provider-presets/OPENCODE_ZEN_FREE/models${refresh ? '?refresh=true' : ''}`))
  },
  async save(body: { apiKey?: string; modelName: string; enabled: boolean; setDefault: boolean }): Promise<ApiResult<PresetStatus>> {
    return apiResultFromResponse(await httpClient.put<ApiResponse<PresetStatus>>('/user/ai-provider-presets/OPENCODE_ZEN_FREE', body))
  },
  async test(body: { apiKey?: string; modelName?: string }): Promise<ApiResult<void>> {
    return apiResultFromResponse(await httpClient.post<ApiResponse<void>>('/user/ai-provider-presets/OPENCODE_ZEN_FREE/test', body))
  },
  async disconnect(): Promise<void> {
    await httpClient.delete('/user/ai-provider-presets/OPENCODE_ZEN_FREE')
  },
}
