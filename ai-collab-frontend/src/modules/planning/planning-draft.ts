import type { TaskPlanDraft } from './types'

export function removeTaskAndDependencies(draft: TaskPlanDraft, tempKey: string): TaskPlanDraft {
  return {
    ...structuredClone(draft),
    tasks: draft.tasks.filter(task => task.tempKey !== tempKey).map(task => ({
      ...task, dependencyTempKeys: task.dependencyTempKeys.filter(key => key !== tempKey),
    })),
  }
}

export function dependencyWouldCycle(draft: TaskPlanDraft, taskKey: string, dependencyKey: string): boolean {
  if (taskKey === dependencyKey) return true
  const dependencies = new Map(draft.tasks.map(task => [task.tempKey, task.dependencyTempKeys]))
  const visit = (key: string, seen: Set<string>): boolean => {
    if (key === taskKey) return true
    if (seen.has(key)) return false
    seen.add(key)
    return (dependencies.get(key) ?? []).some(next => visit(next, seen))
  }
  return visit(dependencyKey, new Set())
}
