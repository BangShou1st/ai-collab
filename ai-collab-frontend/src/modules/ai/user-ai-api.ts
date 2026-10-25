import { httpClient } from '../../api/http-client'
import { apiResultFromResponse } from '../../api/api-result'
import type { ApiResponse, ApiResult } from '../../api/types'
import type { ProbeMode } from './ai-purpose-status'

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

/** 自定义 Provider 接入档案：默认端点与能力候选的后端单一事实源。code 为档案唯一键。 */
export interface ProviderProfile {
  code: string
  providerType: AiProviderType
  label: string
  defaultBaseUrl: string
  defaultApiPath: string
  apiKeyRequired: boolean
  modelDiscovery: boolean
  candidateCapabilities: string[]
  apiPathHint: string
}

/** 落库探针结果（后端动态判定 stale；stale=true 时前端展示为“已过期”）。 */
export interface ProbeResultView {
  mode: ProbeMode
  status: 'PASS' | 'FAIL' | 'NOT_SUPPORTED' | 'INCONCLUSIVE'
  model: string | null
  detail: string | null
  errorCode: string | null
  latencyMs: number | null
  testedAt: string | null
  stale: boolean
}

/** 「检测并配置」单步结论（SKIPPED = 前序确认失败后未发起请求）。 */
export interface DetectStepResult {
  mode: ProbeMode
  status: 'PASS' | 'FAIL' | 'NOT_SUPPORTED' | 'INCONCLUSIVE' | 'SKIPPED'
  detail: string | null
  errorCode: string | null
  latencyMs: number | null
}

/** 「检测并配置」整体结果：探针结论 + 能力写回结果。 */
export interface DetectAndConfigureResult {
  steps: DetectStepResult[]
  writeBack: {
    applied: boolean
    reason: string | null
    capabilitiesAfter: string[]
  }
  generationRequests: number
}

export const userAiApi = {
  async profiles(): Promise<ApiResult<ProviderProfile[]>> {
    return apiResultFromResponse(await httpClient.get<ApiResponse<ProviderProfile[]>>('/user/ai-providers/profiles'))
  },
  async testCapability(id: string, mode: ProbeMode): Promise<ApiResult<unknown>> {
    return apiResultFromResponse(await httpClient.post<ApiResponse<unknown>>(`/user/ai-providers/${id}/test-capability`, {}, { params: { mode } }))
  },
  /** 一键检测并配置：后端串行探针 + PASS 能力 CAS 写回。 */
  async detectAndConfigure(id: string, options: { planning: boolean; nativeTools: boolean }): Promise<ApiResult<DetectAndConfigureResult>> {
    return apiResultFromResponse(await httpClient.post<ApiResponse<DetectAndConfigureResult>>(`/user/ai-providers/${id}/detect-and-configure`, {
      planning: options.planning,
      nativeTools: options.nativeTools,
    }))
  },
  /** 未保存配置的模型发现：输入 Key 后即可获取模型列表；Key 只用于本次只读请求。 */
  async discoverUnsavedModels(payload: {
    providerType: AiProviderType
    baseUrl: string
    apiPath: string
    apiKey: string
  }): Promise<ApiResult<string[]>> {
    return apiResultFromResponse(await httpClient.post<ApiResponse<string[]>>('/user/ai-providers/model-discovery', payload))
  },
  async probeResults(id: string): Promise<ApiResult<ProbeResultView[]>> {
    return apiResultFromResponse(await httpClient.get<ApiResponse<ProbeResultView[]>>(`/user/ai-providers/${id}/probe-results`))
  },
  async models(id: string, refresh = false): Promise<ApiResult<string[]>> {
    return apiResultFromResponse(await httpClient.get<ApiResponse<string[]>>(`/user/ai-providers/${id}/models`, { params: refresh ? { refresh: true } : {} }))
  },
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
  /** 仅返回已保存覆盖的用途；缺失的用途表示回退到默认模型。 */
  async listPurposes(): Promise<ApiResult<Partial<Record<AiPurpose, string>>>> {
    return apiResultFromResponse(await httpClient.get<ApiResponse<Partial<Record<AiPurpose, string>>>>('/user/ai-providers/purposes'))
  },
}
