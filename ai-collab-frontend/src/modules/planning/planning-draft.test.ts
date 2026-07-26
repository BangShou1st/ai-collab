import { describe, expect, it } from 'vitest'
import { dependencyWouldCycle, removeTaskAndDependencies } from './planning-draft'
import type { TaskPlanDraft } from './types'

const draft = {
  summary: '', assumptions: [], risks: [], milestones: [], sources: [],
  tasks: [
    { tempKey: 'a', dependencyTempKeys: ['b'] },
    { tempKey: 'b', dependencyTempKeys: [] },
    { tempKey: 'c', dependencyTempKeys: ['a'] },
  ].map(task => ({ milestoneTempKey: 'm', title: task.tempKey, objective: '', description: '',
    priority: 'MEDIUM' as const, estimatedHours: 1, startDate: null, dueDate: null,
    suggestedAssigneeId: null, assigneeId: null, sourceRefs: [], sortOrder: 0, ...task })),
} satisfies TaskPlanDraft

describe('planning draft safety', () => {
  it('removes inbound dependencies with a deleted task', () => {
    expect(removeTaskAndDependencies(draft, 'a').tasks.find(task => task.tempKey === 'c')?.dependencyTempKeys).toEqual([])
  })
  it('detects a proposed multi-level cycle', () => {
    expect(dependencyWouldCycle(draft, 'b', 'c')).toBe(true)
  })
})
