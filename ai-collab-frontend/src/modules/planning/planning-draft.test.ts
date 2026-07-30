import { describe, expect, it } from 'vitest'
import { dependencyWouldCycle, removeTaskAndDependencies } from './planning-draft'
import type { TaskPlanDraft } from './types'

function makeTask(tempKey: string, deps: string[] = [], milestoneTempKey = 'm') {
  return {
    tempKey, milestoneTempKey, title: tempKey, objective: '', description: '',
    priority: 'MEDIUM' as const, estimatedHours: 1, startDate: null, dueDate: null,
    suggestedAssigneeId: null, assigneeId: null, dependencyTempKeys: deps,
    sourceRefs: [], sortOrder: 0,
  }
}

const draft = {
  summary: '', assumptions: [], risks: [], milestones: [], sources: [],
  tasks: [makeTask('a', ['b']), makeTask('b', []), makeTask('c', ['a'])],
} satisfies TaskPlanDraft

describe('planning draft safety', () => {
  it('removes inbound dependencies with a deleted task', () => {
    const result = removeTaskAndDependencies(draft, 'a')
    expect(result.tasks.find(t => t.tempKey === 'c')?.dependencyTempKeys).toEqual([])
    expect(result.tasks.find(t => t.tempKey === 'a')).toBeUndefined()
  })

  it('detects a proposed multi-level cycle', () => {
    expect(dependencyWouldCycle(draft, 'b', 'c')).toBe(true)
  })

  it('allows non-cyclic dependency', () => {
    expect(dependencyWouldCycle(draft, 'a', 'b')).toBe(false)
  })

  it('rejects self-dependency', () => {
    expect(dependencyWouldCycle(draft, 'a', 'a')).toBe(true)
  })

  it('does not mutate original draft', () => {
    const original = structuredClone(draft)
    removeTaskAndDependencies(draft, 'a')
    expect(draft).toEqual(original)
  })

  it('handles removing a task with no dependencies', () => {
    const result = removeTaskAndDependencies(draft, 'b')
    expect(result.tasks).toHaveLength(2)
    expect(result.tasks.find(t => t.tempKey === 'a')?.dependencyTempKeys).toEqual([])
  })

  it('handles removing a non-existent task', () => {
    const result = removeTaskAndDependencies(draft, 'z')
    expect(result.tasks).toHaveLength(3)
  })

  it('detects cycle through multiple levels', () => {
    const deepDraft = {
      summary: '', assumptions: [], risks: [], milestones: [], sources: [],
      tasks: [
        makeTask('a', ['b']),
        makeTask('b', ['c']),
        makeTask('c', ['d']),
        makeTask('d', ['a']),
      ],
    } satisfies TaskPlanDraft
    expect(dependencyWouldCycle(deepDraft, 'a', 'b')).toBe(true)
  })

  it('handles empty task list', () => {
    const empty = { summary: '', assumptions: [], risks: [], milestones: [], sources: [], tasks: [] } satisfies TaskPlanDraft
    expect(removeTaskAndDependencies(empty, 'a').tasks).toEqual([])
    expect(dependencyWouldCycle(empty, 'a', 'b')).toBe(false)
  })
})
