import { anonymousHttpClient, httpClient } from '../../api/http-client'
import { apiResultFromResponse } from '../../api/api-result'
import type { ApiResponse, ApiResult, LoginResult } from '../../api/types'
import type {
  AcceptInvitationRequest,
  CreateInvitationRequest,
  InvitationCreated,
  InvitationAcceptanceResult,
  InvitationPreview,
  Project,
  ProjectMember,
  ProjectRole,
  ProjectType,
} from './types'

export const projectApi = {
  async list(): Promise<ApiResult<Project[]>> {
    return apiResultFromResponse(await httpClient.get<ApiResponse<Project[]>>('/projects'))
  },
  async get(projectId: string): Promise<ApiResult<Project>> {
    return apiResultFromResponse(await httpClient.get<ApiResponse<Project>>(`/projects/${projectId}`))
  },
  async create(payload: {
    name: string
    description: string
    type: ProjectType
    startDate: string | null
    dueDate: string | null
  }): Promise<ApiResult<Project>> {
    return apiResultFromResponse(await httpClient.post<ApiResponse<Project>>('/projects', payload))
  },
  async update(
    projectId: string,
    payload: {
      name: string
      description: string
      type: ProjectType
      startDate: string | null
      dueDate: string | null
      status: Project['status']
      version: number
    },
  ): Promise<ApiResult<Project>> {
    return apiResultFromResponse(
      await httpClient.patch<ApiResponse<Project>>(`/projects/${projectId}`, payload),
    )
  },
  async remove(projectId: string): Promise<void> {
    await httpClient.delete(`/projects/${projectId}`)
  },
  async listMembers(projectId: string): Promise<ApiResult<ProjectMember[]>> {
    return apiResultFromResponse(
      await httpClient.get<ApiResponse<ProjectMember[]>>(`/projects/${projectId}/members`),
    )
  },
  async createInvitation(
    projectId: string,
    request: CreateInvitationRequest,
  ): Promise<ApiResult<InvitationCreated>> {
    return apiResultFromResponse(
      await httpClient.post<ApiResponse<InvitationCreated>>(
        `/projects/${projectId}/invitations`,
        request,
      ),
    )
  },
  async changeMemberRole(
    projectId: string,
    userId: string,
    role: Exclude<ProjectRole, 'OWNER'>,
  ): Promise<ApiResult<ProjectMember>> {
    return apiResultFromResponse(
      await httpClient.patch<ApiResponse<ProjectMember>>(
        `/projects/${projectId}/members/${userId}/role`,
        { role },
      ),
    )
  },
  async removeMember(projectId: string, userId: string): Promise<void> {
    await httpClient.delete(`/projects/${projectId}/members/${userId}`)
  },
  async previewInvitation(code: string): Promise<ApiResult<InvitationPreview>> {
    return apiResultFromResponse(
      await anonymousHttpClient.get<ApiResponse<InvitationPreview>>(`/invitations/${code}`),
    )
  },
  async acceptInvitationAndRegister(
    code: string,
    request: AcceptInvitationRequest,
  ): Promise<ApiResult<LoginResult>> {
    return apiResultFromResponse(
      await anonymousHttpClient.post<ApiResponse<LoginResult>>(
        `/invitations/${code}/accept`,
        request,
      ),
    )
  },
  async acceptInvitationAsCurrentUser(
    code: string,
  ): Promise<ApiResult<InvitationAcceptanceResult>> {
    return apiResultFromResponse(
      await httpClient.post<ApiResponse<InvitationAcceptanceResult>>(
        `/invitations/${code}/accept-current-user`,
      ),
    )
  },
  async leaveProject(projectId: string): Promise<void> {
    await httpClient.post(`/projects/${projectId}/leave`)
  },
  async transferOwnership(projectId: string, newOwnerId: string): Promise<void> {
    await httpClient.post(`/projects/${projectId}/transfer-ownership`, { newOwnerId })
  },
}
