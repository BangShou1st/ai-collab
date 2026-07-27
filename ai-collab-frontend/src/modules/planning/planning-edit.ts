import type { TaskPlanDraft, PlanTaskDraft, PlanMilestoneDraft, StructuredValidationIssue } from './types'

/**
 * Task 12: Edit utility functions for planning draft editing.
 * Handles dirty state, base version tracking, and local validation.
 */

export interface EditState {
  baseVersionId: string
  serverSnapshot: TaskPlanDraft
  workingDraft: TaskPlanDraft
  isDirty: boolean
}

export function createEditState(versionId: string, draft: TaskPlanDraft): EditState {
  return {
    baseVersionId: versionId,
    serverSnapshot: structuredClone(draft),
    workingDraft: structuredClone(draft),
    isDirty: false,
  }
}

export function markDirty(state: EditState): void {
  state.isDirty = true
}

export function resetDirty(state: EditState): void {
  state.isDirty = false
  state.serverSnapshot = structuredClone(state.workingDraft)
}

export function reloadFromServer(state: EditState, versionId: string, draft: TaskPlanDraft): void {
  state.baseVersionId = versionId
  state.serverSnapshot = structuredClone(draft)
  state.workingDraft = structuredClone(draft)
  state.isDirty = false
}

/**
 * Local validation: check basic constraints before submitting.
 * Returns list of user-friendly error messages.
 */
export function validateLocally(draft: TaskPlanDraft): string[] {
  const errors: string[] = []

  // startDate <= dueDate for tasks
  for (const task of draft.tasks) {
    if (task.startDate && task.dueDate && task.startDate > task.dueDate) {
      errors.push(`任务 ${task.tempKey} 的开始日期晚于截止日期`)
    }
  }

  // No self-dependency
  for (const task of draft.tasks) {
    if (task.dependencyTempKeys.includes(task.tempKey)) {
      errors.push(`任务 ${task.tempKey} 不能依赖自己`)
    }
  }

  // No duplicate dependencies
  for (const task of draft.tasks) {
    const seen = new Set<string>()
    for (const dep of task.dependencyTempKeys) {
      if (seen.has(dep)) {
        errors.push(`任务 ${task.tempKey} 有重复依赖 ${dep}`)
        break
      }
      seen.add(dep)
    }
  }

  return errors
}

/**
 * Build PATCH request body from edit state.
 */
export function buildPatchBody(
  state: EditState,
  milestones: Array<{ tempKey: string; description?: string | null; targetDate?: string | null }>,
  tasks: Array<{ tempKey: string; startDate?: string | null; dueDate?: string | null }>
) {
  return {
    baseVersionId: state.baseVersionId,
    expectedVersionNo: 0,
    milestones: milestones.map(m => ({
      tempKey: m.tempKey,
      description: m.description !== undefined ? { present: true, value: m.description } : { present: false },
      targetDate: m.targetDate !== undefined ? { present: true, value: m.targetDate } : { present: false },
      sourceRefs: { present: false },
    })),
    tasks: tasks.map(t => ({
      tempKey: t.tempKey,
      startDate: t.startDate !== undefined ? { present: true, value: t.startDate } : { present: false },
      dueDate: t.dueDate !== undefined ? { present: true, value: t.dueDate } : { present: false },
      description: { present: false },
      priority: { present: false },
      estimatedHours: { present: false },
      suggestedAssigneeId: { present: false },
      dependencyTempKeys: { present: false },
      sourceRefs: { present: false },
    })),
  }
}
