import type { ProjectRole } from '../project/types'

export interface DashboardProject {
  id: string
  name: string
  description: string
  status: 'ACTIVE' | 'ARCHIVED'
  startDate: string | null
  dueDate: string | null
  currentUserRole: ProjectRole
  memberCount: number | null
}

export interface DashboardTaskStats {
  total: number
  todo: number
  inProgress: number
  blocked: number
  done: number
  canceled: number
  overdue: number
  completionRate: number
}

export interface DashboardMilestoneProgress {
  id: string
  name: string
  status: 'PLANNED' | 'ACTIVE' | 'COMPLETED' | 'CANCELED'
  targetDate: string | null
  totalTasks: number
  completedTasks: number
  completionRate: number
  overdue: boolean
}

export interface DashboardRecentTask {
  id: string
  title: string
  status: string
  priority: string
  assigneeId: string | null
  assigneeDisplayName: string | null
  milestoneId: string | null
  milestoneName: string | null
  dueDate: string | null
  unfinishedDependencyCount: number
  overdue: boolean
  updatedAt: string
}

export interface DashboardRecentDocument {
  id: string
  originalFilename: string
  status: string
  uploadedBy: string
  uploaderDisplayName: string | null
  createdAt: string
}

export interface DashboardActivity {
  id: string
  userDisplayName: string | null
  action: string
  entityType: string
  entityId: string | null
  summary: string
  createdAt: string
}

export interface DashboardView {
  project: DashboardProject
  tasks: DashboardTaskStats
  milestones: DashboardMilestoneProgress[]
  recentTasks: DashboardRecentTask[]
  recentDocuments: DashboardRecentDocument[]
  recentActivities: DashboardActivity[]
}
