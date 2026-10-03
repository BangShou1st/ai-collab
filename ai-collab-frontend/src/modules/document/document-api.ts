import { apiResultFromResponse } from '../../api/api-result'
import { httpClient } from '../../api/http-client'
import type { ApiResponse, ApiResult } from '../../api/types'
import type { DownloadUrl, ProjectDocument, DocumentReadingStatus, DocumentBodyRead } from './types'

export const documentApi = {
  async readingStatus(projectId: string, documentId: string): Promise<ApiResult<DocumentReadingStatus>> {
    return apiResultFromResponse(await httpClient.get<ApiResponse<DocumentReadingStatus>>(`/projects/${projectId}/documents/${documentId}/reading`))
  },
  async readBody(projectId: string, documentId: string, params: { snapshotId?: string | null; fromChunk?: number; fromOffset?: number; maxChars?: number; chunkId?: string }): Promise<ApiResult<DocumentBodyRead>> {
    return apiResultFromResponse(await httpClient.get<ApiResponse<DocumentBodyRead>>(`/projects/${projectId}/documents/${documentId}/body`, { params }))
  },
  async list(projectId: string): Promise<ApiResult<ProjectDocument[]>> {
    return apiResultFromResponse(
      await httpClient.get<ApiResponse<ProjectDocument[]>>(`/projects/${projectId}/documents`),
    )
  },
  async get(projectId: string, documentId: string): Promise<ApiResult<ProjectDocument>> {
    return apiResultFromResponse(
      await httpClient.get<ApiResponse<ProjectDocument>>(
        `/projects/${projectId}/documents/${documentId}`,
      ),
    )
  },
  async upload(projectId: string, file: File, displayName: string): Promise<ApiResult<ProjectDocument>> {
    const body = new FormData()
    body.append('file', file)
    if (displayName.trim()) body.append('displayName', displayName.trim())
    return apiResultFromResponse(
      await httpClient.post<ApiResponse<ProjectDocument>>(
        `/projects/${projectId}/documents`,
        body,
      ),
    )
  },
  async downloadUrl(projectId: string, documentId: string): Promise<ApiResult<DownloadUrl>> {
    return apiResultFromResponse(
      await httpClient.get<ApiResponse<DownloadUrl>>(
        `/projects/${projectId}/documents/${documentId}/download-url`,
      ),
    )
  },
  async retry(projectId: string, documentId: string): Promise<ApiResult<ProjectDocument>> {
    return apiResultFromResponse(
      await httpClient.post<ApiResponse<ProjectDocument>>(
        `/projects/${projectId}/documents/${documentId}/retry`,
      ),
    )
  },
  async reindex(projectId: string, documentId: string): Promise<ApiResult<ProjectDocument>> {
    return apiResultFromResponse(
      await httpClient.post<ApiResponse<ProjectDocument>>(
        `/projects/${projectId}/documents/${documentId}/reindex`,
      ),
    )
  },
  async delete(projectId: string, documentId: string): Promise<void> {
    await httpClient.delete(`/projects/${projectId}/documents/${documentId}`)
  },
  async reindexAll(projectId: string): Promise<ApiResult<{ queued: number }>> {
    return apiResultFromResponse(
      await httpClient.post<ApiResponse<{ queued: number }>>(
        `/projects/${projectId}/documents/reindex-all`,
      ),
    )
  },
  async getReindexProgress(projectId: string): Promise<ApiResult<{ total: number; completed: number; failed: number; inProgress: number }>> {
    return apiResultFromResponse(
      await httpClient.get<ApiResponse<{ total: number; completed: number; failed: number; inProgress: number }>>(
        `/projects/${projectId}/documents/reindex-progress`,
      ),
    )
  },
}
