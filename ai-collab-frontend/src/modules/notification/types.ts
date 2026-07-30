export type NotificationType =
  | 'TASK_ASSIGNED'
  | 'TASK_STATUS_CHANGED'
  | 'TASK_DUE_SOON'
  | 'TASK_OVERDUE'
  | 'DEPENDENCY_COMPLETED'
  | 'DOCUMENT_PROCESSED'
  | 'DOCUMENT_FAILED'
  | 'PLAN_CONFIRMED'
  | 'COMMENT_ADDED'
  | 'MILESTONE_COMPLETED'

export interface Notification {
  id: string
  projectId: string
  projectName: string
  type: NotificationType
  title: string
  content: string | null
  entityType: string | null
  entityId: string | null
  read: boolean
  createdAt: string
}
