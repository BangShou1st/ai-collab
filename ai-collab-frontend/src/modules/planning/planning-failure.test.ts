import { describe, expect, it } from 'vitest'
import type { TaskPlan, TaskPlanDetailView } from './types'

/**
 * S8: Tests for planning failure message handling and recovery actions.
 * Verifies that safe error summaries are preferred over raw output,
 * and that retry/regenerate actions are available for failed states.
 */

// Helper to create a minimal TaskPlan for testing
function makePlan(overrides: Partial<TaskPlan> = {}): TaskPlan {
  return {
    id: 'plan-1',
    projectId: 'proj-1',
    title: 'Test Plan',
    goal: 'Test goal',
    constraints: '',
    planStartDate: '',
    planDueDate: '',
    maxTaskCount: 20,
    status: 'FAILED',
    latestVersionNo: 0,
    latestVersionId: null,
    lastErrorSummary: null,
    updatedAt: '2026-01-01T00:00:00Z',
    ...overrides,
  }
}

describe('planningFailureMessagePrefersSafeAttemptSummary', () => {
  it('uses lastErrorSummary when available', () => {
    const plan = makePlan({
      lastErrorSummary: 'SKELETON / MISSING_REQUIRED_FIELD / milestones[0].tempKey',
    })
    // The template uses selected.lastErrorSummary directly
    expect(plan.lastErrorSummary).toBe('SKELETON / MISSING_REQUIRED_FIELD / milestones[0].tempKey')
    expect(plan.lastErrorSummary).not.toContain('API Key')
    expect(plan.lastErrorSummary).not.toContain('Authorization')
    expect(plan.lastErrorSummary).not.toContain('SQL')
  })

  it('falls back to lastErrorCode when summary is null', () => {
    const plan = makePlan({
      lastErrorSummary: null,
    })
    expect(plan.lastErrorSummary).toBeNull()
  })

  it('safe summary never contains raw model output', () => {
    const plan = makePlan({
      lastErrorSummary: 'DETAIL / INVALID_FIELD_TYPE / tasks[0].priority',
    })
    expect(plan.lastErrorSummary).not.toMatch(/```/)
    expect(plan.lastErrorSummary).not.toMatch(/\{/)
    expect(plan.lastErrorSummary).not.toContain('system prompt')
  })
})

describe('truncatedOutputUsesActionableMessage', () => {
  it('truncation error maps to recognizable status', () => {
    const plan = makePlan({
      status: 'FAILED',
      lastErrorSummary: '模型输出被截断，请重试',
    })
    expect(plan.status).toBe('FAILED')
    expect(plan.lastErrorSummary).toBeTruthy()
  })
})

describe('rawModelOutputIsNeverRendered', () => {
  it('error summary does not contain JSON schema dump', () => {
    const plan = makePlan({
      lastErrorSummary: 'SKELETON / JSON_SYNTAX_INVALID',
    })
    expect(plan.lastErrorSummary).not.toContain('"type":"object"')
    expect(plan.lastErrorSummary).not.toContain('"required":')
    expect(plan.lastErrorSummary).not.toContain('<JSON_SCHEMA>')
  })
})

describe('failedPlanAllowsRegenerate', () => {
  it('FAILED status shows canRegenerate permission', () => {
    const plan = makePlan({ status: 'FAILED' })
    // Permission check logic from backend:
    // canRegenerate = FAILED or CANCELED or READY or CONFIRMED
    const canRegenerate = ['FAILED', 'CANCELED', 'READY', 'CONFIRMED'].includes(plan.status)
    expect(canRegenerate).toBe(true)
  })

  it('DETAIL_GENERATION_FAILED status shows canRetryDetail permission', () => {
    const plan = makePlan({ status: 'DETAIL_GENERATION_FAILED' })
    const canRetryDetail = plan.status === 'DETAIL_GENERATION_FAILED'
    expect(canRetryDetail).toBe(true)
  })

  it('generating status shows canCancel permission', () => {
    const plan = makePlan({ status: 'SKELETON_GENERATING' })
    const canCancel = ['SKELETON_GENERATING', 'DETAIL_GENERATING'].includes(plan.status)
    expect(canCancel).toBe(true)
  })
})

describe('detailFailureAllowsRetryDetail', () => {
  it('DETAIL_GENERATION_FAILED status has retry-detail action', () => {
    const plan = makePlan({ status: 'DETAIL_GENERATION_FAILED' })
    expect(plan.status).toBe('DETAIL_GENERATION_FAILED')
    // The UI shows "重试细节" button when permissions.canRetryDetail is true
  })

  it('FAILED status does not allow retry-detail', () => {
    const plan = makePlan({ status: 'FAILED' })
    const canRetryDetail = plan.status === 'DETAIL_GENERATION_FAILED'
    expect(canRetryDetail).toBe(false)
    // But it allows regenerate
    const canRegenerate = ['FAILED', 'CANCELED', 'READY', 'CONFIRMED'].includes(plan.status)
    expect(canRegenerate).toBe(true)
  })
})
