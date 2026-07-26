import { apiResultFromResponse } from '../../api/api-result'
import { httpClient } from '../../api/http-client'
import type { ApiResponse, ApiResult } from '../../api/types'
import type { DownloadUrl, ProjectDocument } from './types'

export const documentApi = {
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
  async delete(projectId: string, documentId: string): Promise<void> {
    await httpClient.delete(`/projects/${projectId}/documents/${documentId}`)
  },
}
