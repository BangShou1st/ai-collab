export type PlanStatus = 'SKELETON_GENERATING' | 'DETAIL_GENERATING' | 'DETAIL_GENERATION_FAILED'
  | 'READY' | 'CONFIRMING' | 'CONFIRMED' | 'FAILED' | 'CANCELED'

export interface TaskPlan {
  id: string
  projectId: string
  title: string
  goal: string
  constraints: string
  planStartDate: string
  planDueDate: string
  maxTaskCount: 10 | 20 | 30 | 40
  status: PlanStatus
  latestVersionNo: number
  latestVersionId: string | null
  lastErrorSummary: string | null
  updatedAt: string
}

export interface PlanPermissions {
  canEdit: boolean; canCancel: boolean; canRetryDetail: boolean
  canRegenerate: boolean; canConfirm: boolean; canDelete: boolean; canRestore: boolean
}

export interface PlanTaskDraft {
  tempKey: string; milestoneTempKey: string; title: string; objective: string; description: string
  priority: 'LOW' | 'MEDIUM' | 'HIGH' | 'URGENT'; estimatedHours: number | null
  startDate: string | null; dueDate: string | null; suggestedAssigneeId: string | null
  assigneeId: string | null; dependencyTempKeys: string[]; sourceRefs: string[]; sortOrder: number
}

export interface TaskPlanDraft {
  summary: string; assumptions: string[]; risks: string[]
  milestones: Array<{ tempKey: string; title: string; objective: string; targetDate: string | null; sortOrder: number; sourceRefs: string[] }>
  tasks: PlanTaskDraft[]
  sources: Array<{ ref: string; documentId: string; documentName: string; quoteText: string }>
}
