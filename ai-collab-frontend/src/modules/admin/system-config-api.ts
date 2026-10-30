import { httpClient } from '../../api/http-client'
import { apiResultFromResponse } from '../../api/api-result'
import type { ApiResponse, ApiResult } from '../../api/types'

export interface SystemConfig {
  id: string
  agentEnabled: boolean
  chatEnabled: boolean
  workerConcurrency: number
  workerDelayMs: number
  scheduleDelayMs: number
  systemMaintenanceMode: boolean
  systemMaintenanceMessage: string
  publicRegistrationEnabled: boolean
  defaultChatModel: string
  defaultAgentModel: string
  ragRetrievalThreshold: number
  ragMaxSources: number
  version: number
  updatedBy: string | null
  createdAt: string
  updatedAt: string
}

export interface UpdateSystemConfigRequest {
  agentEnabled: boolean
  chatEnabled: boolean
  workerConcurrency: number
  workerDelayMs: number
  scheduleDelayMs: number
  systemMaintenanceMode: boolean
  systemMaintenanceMessage: string
  publicRegistrationEnabled: boolean
  defaultChatModel: string
  defaultAgentModel: string
  ragRetrievalThreshold: number
  ragMaxSources: number
  expectedVersion: number
}

export interface SystemConfigAudit {
  id: string
  configVersion: number
  action: 'INITIALIZE' | 'UPDATE' | 'RESTORE' | string
  changedBy: string | null
  changedByName: string | null
  changeSummary: string | null
  configSnapshot: string
  appliedStatus: 'APPLIED' | 'FAILED' | 'PENDING' | string
  errorMessage: string | null
  createdAt: string
}

export interface SystemRuntimeStatus {
  agentEnabled: boolean
  chatEnabled: boolean
  agentRuntimeAllowed: boolean
  configuredWorkerConcurrency: number
  activeWorkerThreads: number
  queuedAgentRuns: number
  runningAgentRuns: number
  recentFailedAgentRuns: number
  lastExecutionError: string | null
  maintenanceMode: boolean
  refreshedAt: string
}

export const systemConfigApi = {
  async getConfig(): Promise<ApiResult<SystemConfig>> {
    return apiResultFromResponse(
      await httpClient.get<ApiResponse<SystemConfig>>('/admin/config'),
    )
  },

  async updateConfig(data: UpdateSystemConfigRequest): Promise<ApiResult<SystemConfig>> {
    return apiResultFromResponse(
      await httpClient.put<ApiResponse<SystemConfig>>('/admin/config', data),
    )
  },

  async validateConfig(data: UpdateSystemConfigRequest): Promise<void> {
    await httpClient.post('/admin/config/validate', data)
  },

  async getAuditLogs(limit = 20): Promise<ApiResult<SystemConfigAudit[]>> {
    return apiResultFromResponse(
      await httpClient.get<ApiResponse<SystemConfigAudit[]>>(`/admin/config/audit?limit=${limit}`),
    )
  },

  async restoreConfig(auditId: string): Promise<ApiResult<SystemConfig>> {
    return apiResultFromResponse(
      await httpClient.post<ApiResponse<SystemConfig>>(`/admin/config/restore/${auditId}`),
    )
  },

  async getRuntimeStatus(): Promise<ApiResult<SystemRuntimeStatus>> {
    return apiResultFromResponse(
      await httpClient.get<ApiResponse<SystemRuntimeStatus>>('/admin/config/runtime-status'),
    )
  },
}
