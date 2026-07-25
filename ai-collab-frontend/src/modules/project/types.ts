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
