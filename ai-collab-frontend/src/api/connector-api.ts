import { httpClient } from './http-client'
import { apiResultFromResponse } from './api-result'
import type { ApiResult, ApiResponse } from './types'

export interface ConnectorConnection {
  id: string
  name: string
  appId: string
  status: 'ACTIVE' | 'INVALID'
  lastVerifiedAt: string | null
  lastError: string | null
  createdAt: string
}

export interface ConnectorSyncJob {
  id: string
  triggerType: 'MANUAL' | 'SCHEDULED'
  status: 'RUNNING' | 'SUCCESS' | 'PARTIAL_FAILED' | 'FAILED'
  startedAt: string
  finishedAt: string | null
  scanned: number
  updated: number
  skipped: number
  failed: number
  removed: number
  errorSummary: string | null
}

export interface ConnectorSource {
  id: string
  projectId: string
  connectionId: string
  connectionName: string | null
  name: string
  status: 'ACTIVE' | 'BLOCKED'
  authorizationDeclaredAt: string
  lastVerifiedAt: string | null
  lastSyncedAt: string | null
  documentCount: number
  lastJob: ConnectorSyncJob | null
  userAuthorizationId?: string | null
}

export interface ConnectorGrant {
  id: string
  connectionId: string
  status: 'ACTIVE' | 'REFRESHING' | 'REFRESH_UNCERTAIN' | 'REAUTH_REQUIRED' | 'REVOKED'
  accessExpiresAt: string | null
}

export interface ConnectorConnectionOption { id: string; name: string }

export interface ExternalDocument {
  id: string
  externalKey: string
  title: string | null
  sourceUrl: string | null
  status: 'ACTIVE' | 'BLOCKED' | 'REMOVED'
  documentId: string | null
  documentStatus: string | null
}

export interface CreateConnectionInput {
  name: string
  appId: string
  appSecret: string
  projectId?: string | null
}

export interface CreateSourceInput {
  connectionId: string
  folderToken: string
  name: string
  authorizationConfirmed: boolean
  userAuthorizationId?: string | null
}

function root(projectId: string): string {
  return `/projects/${projectId}/connector/sources`
}

export const connectorApi = {
  async availableConnections(projectId: string): Promise<ApiResult<ConnectorConnectionOption[]>> {
    return apiResultFromResponse(await httpClient.get<ApiResponse<ConnectorConnectionOption[]>>(`/projects/${projectId}/connector/available-connections`))
  },
  async authorizations(projectId: string): Promise<ApiResult<ConnectorGrant[]>> {
    return apiResultFromResponse(await httpClient.get<ApiResponse<ConnectorGrant[]>>(`/projects/${projectId}/connector/authorizations`))
  },
  async startAuthorization(projectId: string, connectionId: string): Promise<ApiResult<{ authorizationUrl: string; expiresAt: string }>> {
    return apiResultFromResponse(await httpClient.post<ApiResponse<{ authorizationUrl: string; expiresAt: string }>>(`/projects/${projectId}/connector/connections/${connectionId}/oauth/start`))
  },
  async disconnectAuthorization(projectId: string, authorizationId: string): Promise<void> {
    await httpClient.delete(`/projects/${projectId}/connector/authorizations/${authorizationId}`)
  },
  async listConnections(): Promise<ApiResult<ConnectorConnection[]>> {
    return apiResultFromResponse(await httpClient.get<ApiResponse<ConnectorConnection[]>>('/connector/connections'))
  },
  async createConnection(input: CreateConnectionInput): Promise<ApiResult<ConnectorConnection>> {
    return apiResultFromResponse(await httpClient.post<ApiResponse<ConnectorConnection>>('/connector/connections', input))
  },
  async configureEvents(connectionId: string, encryptKey: string, verificationToken: string): Promise<void> {
    await httpClient.put(`/connector/connections/${connectionId}/event-credentials`, { encryptKey, verificationToken })
  },
  async verifyConnection(connectionId: string): Promise<ApiResult<ConnectorConnection>> {
    return apiResultFromResponse(await httpClient.post<ApiResponse<ConnectorConnection>>(`/connector/connections/${connectionId}/verify`))
  },
  async deleteConnection(connectionId: string): Promise<ApiResult<null>> {
    return apiResultFromResponse(await httpClient.delete<ApiResponse<null>>(`/connector/connections/${connectionId}`))
  },
  async listSources(projectId: string): Promise<ApiResult<ConnectorSource[]>> {
    return apiResultFromResponse(await httpClient.get<ApiResponse<ConnectorSource[]>>(root(projectId)))
  },
  async createSource(projectId: string, input: CreateSourceInput): Promise<ApiResult<ConnectorSource>> {
    return apiResultFromResponse(await httpClient.post<ApiResponse<ConnectorSource>>(root(projectId), input))
  },
  async listSourceDocuments(projectId: string, sourceId: string): Promise<ApiResult<ExternalDocument[]>> {
    return apiResultFromResponse(await httpClient.get<ApiResponse<ExternalDocument[]>>(`${root(projectId)}/${sourceId}/documents`))
  },
  async syncSource(projectId: string, sourceId: string): Promise<ApiResult<{ jobId: string; status: string }>> {
    return apiResultFromResponse(await httpClient.post<ApiResponse<{ jobId: string; status: string }>>(`${root(projectId)}/${sourceId}/sync`))
  },
  async sourceStatus(projectId: string, sourceId: string): Promise<ApiResult<ConnectorSyncJob | null>> {
    return apiResultFromResponse(await httpClient.get<ApiResponse<ConnectorSyncJob | null>>(`${root(projectId)}/${sourceId}/status`))
  },
}
