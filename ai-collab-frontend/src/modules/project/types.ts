export type ProjectRole = 'OWNER' | 'ADMIN' | 'MEMBER'

export interface Project {
  id: string
  name: string
  description: string
  startDate: string | null
  dueDate: string | null
  status: 'ACTIVE' | 'ARCHIVED'
  version: number
  role: ProjectRole
}

export interface ProjectMember {
  userId: string
  username: string
  displayName: string
  role: ProjectRole
  joinedAt: string
}

export interface InvitationPreview {
  projectId: string
  projectName: string
  role: ProjectRole
  invitedEmail: string | null
  expiresAt: string
}

export interface InvitationAcceptanceResult {
  projectId: string
  projectName: string
  role: ProjectRole
  alreadyMember: boolean
}

export interface InvitationCreated {
  id: string
  code: string
  projectId: string
  role: ProjectRole
  expiresAt: string
}

export interface CreateInvitationRequest {
  role: Exclude<ProjectRole, 'OWNER'>
  invitedEmail: string | null
  expiresInHours: number
}

export interface AcceptInvitationRequest {
  username: string
  displayName: string
  email: string | null
  password: string
}
