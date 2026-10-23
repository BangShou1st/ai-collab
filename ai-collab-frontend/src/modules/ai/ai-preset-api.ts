import { httpClient } from '../../api/http-client'
import { apiResultFromResponse } from '../../api/api-result'
import type { ApiResponse, ApiResult } from '../../api/types'

export interface PresetStatus { code: string; connected: boolean; modelName: string | null; enabled: boolean; isDefault: boolean }

export const aiPresetApi = {
  async presets(): Promise<ApiResult<PresetStatus[]>> {
    return apiResultFromResponse(await httpClient.get<ApiResponse<PresetStatus[]>>('/user/ai-provider-presets'))
  },
  async models(code: string, refresh = false): Promise<ApiResult<string[]>> {
    return apiResultFromResponse(await httpClient.get<ApiResponse<string[]>>(`/user/ai-provider-presets/${code}/models${refresh ? '?refresh=true' : ''}`))
  },
  async save(code: string, body: { apiKey?: string; modelName: string; enabled: boolean; setDefault: boolean }): Promise<ApiResult<PresetStatus>> {
    return apiResultFromResponse(await httpClient.put<ApiResponse<PresetStatus>>(`/user/ai-provider-presets/${code}`, body))
  },
  async disconnect(code: string): Promise<void> {
    await httpClient.delete(`/user/ai-provider-presets/${code}`)
  },
}
