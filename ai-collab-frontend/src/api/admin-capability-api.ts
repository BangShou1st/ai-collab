import { httpClient } from './http-client'
import { apiResultFromResponse } from './api-result'
import type { ApiResponse, ApiResult } from './types'

export type CapabilityProfileStatus = 'DRAFT' | 'TESTED' | 'PUBLISHED' | 'DISABLED'
export type CapabilityType = 'CHAT' | 'EMBEDDING' | 'RERANK'

export interface CapabilityProfileView {
  id: string
  modelProfileId: string
  modelKey: string
  modelDisplayName: string
  providerId: string
  providerName: string
  capability: CapabilityType
  protocol: string
  settings: Record<string, unknown>
  status: CapabilityProfileStatus
  version: number
  source: string
  verifiedAt: string | null
  verifiedBy: string | null
  createdAt: string
  updatedAt: string
}

export interface CapabilityTestDocument {
  id: string
  title: string
  text: string
}

export interface CapabilityTestRequest {
  query?: string
  documents?: CapabilityTestDocument[]
  topK?: number
  reason?: string
}

export interface CapabilityTestScore {
  index: number
  id: string
  score: number
}

export interface CapabilityTestResponse {
  profileId: string
  providerId: string
  profileStatus: string
  profileVersion: number
  outcome: string
  reason: string | null
  scores: CapabilityTestScore[]
  modelLatencyMs: number | null
  totalMs: number
  overheadMs: number
  candidateCount: number
  topK: number
}

export interface CapabilityAssignmentView {
  id: string
  capability: CapabilityType
  purpose: string
  scope: 'GLOBAL' | 'PROJECT'
  projectId: string | null
  profileId: string
  profileStatus: string | null
  profileVersion: number
  enabled: boolean
  updatedAt: string
}

const PROFILE_BASE = '/admin/ai/capability-profiles'
const ASSIGNMENT_BASE = '/admin/ai/capability-assignments'

export const adminCapabilityApi = {
  async listProfiles(): Promise<ApiResult<CapabilityProfileView[]>> {
    const res = await httpClient.get<ApiResponse<CapabilityProfileView[]>>(PROFILE_BASE)
    return apiResultFromResponse(res)
  },

  async testProfile(
    id: string,
    request: CapabilityTestRequest,
  ): Promise<ApiResult<CapabilityTestResponse>> {
    const res = await httpClient.post<ApiResponse<CapabilityTestResponse>>(
      `${PROFILE_BASE}/${id}/test`,
      request,
    )
    return apiResultFromResponse(res)
  },

  async publish(id: string, reason?: string): Promise<ApiResult<CapabilityProfileView>> {
    const res = await httpClient.post<ApiResponse<CapabilityProfileView>>(
      `${PROFILE_BASE}/${id}/publish`,
      reason ? { reason } : {},
    )
    return apiResultFromResponse(res)
  },

  async rollback(id: string, reason?: string): Promise<ApiResult<CapabilityProfileView>> {
    const res = await httpClient.post<ApiResponse<CapabilityProfileView>>(
      `${PROFILE_BASE}/${id}/rollback`,
      reason ? { reason } : {},
    )
    return apiResultFromResponse(res)
  },

  async disable(id: string, reason?: string): Promise<ApiResult<CapabilityProfileView>> {
    const res = await httpClient.post<ApiResponse<CapabilityProfileView>>(
      `${PROFILE_BASE}/${id}/disable`,
      reason ? { reason } : {},
    )
    return apiResultFromResponse(res)
  },

  async listAssignments(): Promise<ApiResult<CapabilityAssignmentView[]>> {
    const res = await httpClient.get<ApiResponse<CapabilityAssignmentView[]>>(ASSIGNMENT_BASE)
    return apiResultFromResponse(res)
  },

  async bindGlobal(
    profileId: string,
    reason?: string,
  ): Promise<ApiResult<CapabilityAssignmentView>> {
    const res = await httpClient.post<ApiResponse<CapabilityAssignmentView>>(ASSIGNMENT_BASE, {
      profileId,
      enabled: true,
      reason,
    })
    return apiResultFromResponse(res)
  },

  async deleteAssignment(id: string): Promise<ApiResult<void>> {
    const res = await httpClient.delete<ApiResponse<void>>(`${ASSIGNMENT_BASE}/${id}`)
    return apiResultFromResponse(res)
  },
}
