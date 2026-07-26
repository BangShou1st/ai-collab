export type TaskStatus = 'TODO' | 'IN_PROGRESS' | 'BLOCKED' | 'DONE' | 'CANCELED'
export type TaskPriority = 'LOW' | 'MEDIUM' | 'HIGH' | 'URGENT'
export type MilestoneStatus = 'PLANNED' | 'ACTIVE' | 'COMPLETED' | 'CANCELED'

export interface Milestone {
  id: string
  projectId: string
  name: string
  description: string
  targetDate: string | null
  status: MilestoneStatus
  sortOrder: number
  version: number
}

export interface Task {
  id: string
  projectId: string
  title: string
  description: string
  milestoneId: string | null
  milestoneName: string | null
  assigneeId: string | null
  assigneeDisplayName: string | null
  status: TaskStatus
  priority: TaskPriority
  estimateHours: number | null
  startDate: string | null
  dueDate: string | null
  version: number
  unfinishedDependencyCount: number
  dependencyIds: string[]
  sourcePlanId: string | null
}

export interface TaskComment {
  id: string
  taskId: string
  authorId: string
  authorDisplayName: string
  content: string
  createdAt: string
  updatedAt: string
}
