export type PlanStatus = 'SKELETON_GENERATING' | 'DETAIL_GENERATING' | 'REPAIRING' | 'DETAIL_GENERATION_FAILED'
  | 'READY' | 'READY_WITH_ISSUES' | 'CONFIRMING' | 'CONFIRMED' | 'FAILED' | 'CANCELED'

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
  canPartialRegenerate: boolean
}

export interface PlanTaskDraft {
  tempKey: string; milestoneTempKey: string; title: string; objective: string; description: string
  priority: 'LOW' | 'MEDIUM' | 'HIGH' | 'URGENT'; estimatedHours: number | null
  startDate: string | null; dueDate: string | null; suggestedAssigneeId: string | null
  assigneeId: string | null; dependencyTempKeys: string[]; sourceRefs: string[]; sortOrder: number
}

export interface PlanMilestoneDraft {
  tempKey: string; title: string; objective: string; description: string
  targetDate: string | null; sortOrder: number; sourceRefs: string[]
}

export interface TaskPlanDraft {
  summary: string; assumptions: string[]; risks: string[]
  milestones: PlanMilestoneDraft[]
  tasks: PlanTaskDraft[]
  sources: PlanSource[]
}

export interface PlanSource {
  ref: string; documentId: string; documentName: string
  chunkId: string | null; heading: string | null
  similarity: number | null; quoteText: string; contentHash: string | null
}

export interface TaskPlanAttemptView {
  id: string; stage: string; status: string
  provider: string | null; model: string | null
  startedAt: string | null; finishedAt: string | null
  latencyMs: number | null; promptTokens: number | null; completionTokens: number | null
  errorCode: string | null; errorSummary: string | null; createdBy: string
}

export interface TaskPlanConfirmationView {
  confirmationId: string; status: string
  milestoneIds: string[]; taskIds: string[]; dependencyCount: number
}

export interface TaskPlanValidationView {
  errors: string[]; warnings: string[]
}

export interface StructuredValidationIssue {
  id: string
  code: string; severity: 'HARD' | 'BLOCKING_EDITABLE' | 'WARNING'
  targetType: string | null; targetTempKey: string | null
  field: string | null; relatedTempKey: string | null
  safeDetails: Record<string, unknown>
}

export type PartialRepairMode =
  | 'REPAIR_DATES_AND_DEPENDENCIES'
  | 'REGENERATE_SELECTED_TASK_DETAILS'
  | 'REPAIR_ASSIGNMENTS_AND_SOURCES'
  | 'REPAIR_ALL_ISSUES'
  | 'RESCHEDULE_UNLOCKED_TASKS'
  | 'APPLY_UPDATED_CONSTRAINTS'

export interface PartialRegenerateRequest {
  baseVersionId: string
  expectedVersionNo: number
  targetTempKeys: string[]
  allowedFields: string[]
  lockedFields: string[]
  issueIds: string[]
  mode: PartialRepairMode
}

export interface TaskPlanEvent {
  id: string; fromVersionId: string | null; toVersionId: string | null
  eventType: string; changedFields: string[]; changedTargets: string[]
  issueCodes: string[]; comment: string | null
  createdAt: string
}

export interface TaskPlanDetailView {
  plan: TaskPlan
  latestVersion: { id: string; versionNo: number; sourceType: string; basedOnVersionId: string | null; createdBy: string; createdAt: string } | null
  activeAttempt: TaskPlanAttemptView | null
  latestFailedAttempt: TaskPlanAttemptView | null
  latestAttempt: TaskPlanAttemptView | null
  confirmation: TaskPlanConfirmationView | null
  validation: TaskPlanValidationView
  permissions: PlanPermissions
  structuredIssues: StructuredValidationIssue[]
}
