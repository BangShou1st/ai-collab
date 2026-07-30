export type ProjectRole = 'OWNER' | 'ADMIN' | 'MEMBER'

export type ProjectType = 'COMPETITION' | 'COURSE_DESIGN' | 'SOFTWARE_TRAINING' | 'OTHER'

export type ProjectStatus = 'PREPARING' | 'ACTIVE' | 'COMPLETED' | 'ARCHIVED'

export interface Project {
  id: string
  name: string
  description: string
  type: ProjectType
  startDate: string | null
  dueDate: string | null
  status: ProjectStatus
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
