import { apiResultFromResponse } from '../../api/api-result'
import { httpClient } from '../../api/http-client'
import type { ApiResponse, ApiResult } from '../../api/types'

export type AgentMemoryType = 'DECISION' | 'PREFERENCE' | 'CONSTRAINT' | 'LESSON'
export type AgentMemoryStatus = 'ACTIVE' | 'DISABLED'
export type AgentMemoryJobStatus =
  | 'QUEUED'
  | 'LEASED'
  | 'RUNNING'
  | 'SUCCEEDED'
  | 'FAILED_RETRYABLE'
  | 'FAILED'
  | 'CANCELED'
  | 'EXPIRED'
export type AgentMemoryCandidateReviewStatus = 'PENDING_REVIEW' | 'ACCEPTED' | 'REJECTED'

export interface AgentMemory {
  id: string
  projectId: string
  type: AgentMemoryType
  title: string
  content: string
  sourceType: string
  sourceId: string | null
  status: AgentMemoryStatus
  createdBy: string | null
  updatedBy: string | null
  version: number
  createdAt: string
  updatedAt: string
  verificationState: string | null
  verifiedAt: string | null
  verifiedBy: string | null
  supersededBy: string | null
}

export interface AgentMemoryInput {
  type: AgentMemoryType
  title: string
  content: string
  sourceType: string
  sourceId?: string | null
  version: number
  verificationState?: string | null
  reason?: string | null
}

export interface AgentMemoryCandidate {
  id: string
  jobId: string
  attemptNo: number
  kind: string
  title: string
  content: string
  confidence: number | null
  sourceRef: string | null
  reviewStatus: AgentMemoryCandidateReviewStatus
  createdAt: string
}

export interface AgentMemoryJob {
  id: string
  projectId: string
  jobType: string
  status: AgentMemoryJobStatus
  attemptCount: number
  maxAttempts: number
  leaseEpoch: number
  leaseExpiresAt: string | null
  nextAttemptAt: string | null
  deadlineAt: string | null
  lastErrorCode: string | null
  createdAt: string
  updatedAt: string
}

const root = (projectId: string) => `/projects/${projectId}/agent`

export const agentMemoryApi = {
  async listMemories(
    projectId: string,
    activeOnly = true,
  ): Promise<ApiResult<AgentMemory[]>> {
    return apiResultFromResponse(await httpClient.get<ApiResponse<AgentMemory[]>>(
      `${root(projectId)}/memories`,
      { params: { activeOnly } },
    ))
  },

  async createMemory(
    projectId: string,
    input: AgentMemoryInput,
  ): Promise<ApiResult<AgentMemory>> {
    return apiResultFromResponse(await httpClient.post<ApiResponse<AgentMemory>>(
      `${root(projectId)}/memories`,
      input,
    ))
  },

  async updateMemory(
    projectId: string,
    memoryId: string,
    input: AgentMemoryInput,
  ): Promise<ApiResult<AgentMemory>> {
    return apiResultFromResponse(await httpClient.patch<ApiResponse<AgentMemory>>(
      `${root(projectId)}/memories/${memoryId}`,
      input,
    ))
  },

  async disableMemory(
    projectId: string,
    memoryId: string,
    version: number,
    reason?: string,
  ): Promise<ApiResult<AgentMemory>> {
    return apiResultFromResponse(await httpClient.delete<ApiResponse<AgentMemory>>(
      `${root(projectId)}/memories/${memoryId}`,
      { params: { version, reason: reason?.trim() || undefined } },
    ))
  },

  async listCandidates(
    projectId: string,
    reviewStatus: AgentMemoryCandidateReviewStatus = 'PENDING_REVIEW',
  ): Promise<ApiResult<AgentMemoryCandidate[]>> {
    return apiResultFromResponse(await httpClient.get<ApiResponse<AgentMemoryCandidate[]>>(
      `${root(projectId)}/memory-candidates`,
      { params: { reviewStatus } },
    ))
  },

  async approveCandidate(
    projectId: string,
    candidateId: string,
    reason?: string,
  ): Promise<ApiResult<AgentMemory>> {
    return apiResultFromResponse(await httpClient.post<ApiResponse<AgentMemory>>(
      `${root(projectId)}/memory-candidates/${candidateId}/approve`,
      reason?.trim() ? { reason: reason.trim() } : undefined,
    ))
  },

  async rejectCandidate(
    projectId: string,
    candidateId: string,
    reason: string,
  ): Promise<ApiResult<AgentMemoryCandidate>> {
    return apiResultFromResponse(await httpClient.post<ApiResponse<AgentMemoryCandidate>>(
      `${root(projectId)}/memory-candidates/${candidateId}/reject`,
      { reason: reason.trim() },
    ))
  },

  async createJob(projectId: string): Promise<ApiResult<AgentMemoryJob>> {
    return apiResultFromResponse(await httpClient.post<ApiResponse<AgentMemoryJob>>(
      `${root(projectId)}/memory-jobs`,
    ))
  },

  async getJob(projectId: string, jobId: string): Promise<ApiResult<AgentMemoryJob>> {
    return apiResultFromResponse(await httpClient.get<ApiResponse<AgentMemoryJob>>(
      `${root(projectId)}/memory-jobs/${jobId}`,
    ))
  },
}
