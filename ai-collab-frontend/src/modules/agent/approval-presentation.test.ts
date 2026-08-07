import { describe, expect, it } from 'vitest'
import type { AgentApproval } from './types'
import { presentApproval } from './approval-presentation'

function approval(overrides: Partial<AgentApproval> = {}): AgentApproval {
  return {
    id: 'approval-1',
    runId: 'run-1',
    toolName: 'create_task_after_approval',
    arguments: {},
    diff: {
      operation: 'CREATE',
      after: {
        title: '完成 Agent 浏览器验收',
        status: 'TODO',
        dueDate: '2026-08-05',
        priority: 'HIGH',
        startDate: null,
        assigneeId: null,
        description: null,
        milestoneId: null,
        estimateHours: null,
      },
    },
    status: 'PENDING',
    nonce: 'nonce',
    expiresAt: '2026-08-01T00:00:00Z',
    createdAt: '2026-07-30T00:00:00Z',
    result: null,
    rejectionReason: null,
    // V37 新增字段
    sessionId: 'session-1',
    proposalFamily: 'TASK_CREATE',
    subjectKey: 'subject-1',
    revision: 1,
    updatedAt: '2026-07-30T00:00:00Z',
    ...overrides,
  }
}

describe('approval presentation', () => {
  it('turns a task proposal into a Chinese user-facing summary', () => {
    const result = presentApproval(approval())

    expect(result.actionLabel).toBe('创建任务')
    expect(result.statusLabel).toBe('待审批')
    expect(result.fields).toContainEqual({
      label: '任务名称',
      value: '完成 Agent 浏览器验收',
    })
    expect(result.fields).toContainEqual({ label: '状态', value: '待处理' })
    expect(result.fields).toContainEqual({ label: '优先级', value: '高' })
    expect(result.fields).toContainEqual({ label: '截止日期', value: '2026年08月05日' })
    expect(JSON.stringify(result)).not.toContain('create_task_after_approval')
    expect(JSON.stringify(result)).not.toContain('"operation"')
  })

  it('translates resolved approval states without leaking English values', () => {
    const result = presentApproval(approval({ status: 'APPROVED' }))

    expect(result.statusLabel).toBe('已批准')
    expect(JSON.stringify(result)).not.toContain('APPROVED')
  })
})
