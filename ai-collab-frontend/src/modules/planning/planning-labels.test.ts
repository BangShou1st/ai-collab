import { describe, expect, it } from 'vitest'
import {
  eventLabel, issueLabel, planStatusLabel, priorityLabel, versionSourceLabel,
} from './planning-labels'

describe('planning Chinese labels', () => {
  it('localizes statuses, version sources and priorities', () => {
    expect(planStatusLabel('REPAIRING')).toBe('正在修复规划问题')
    expect(planStatusLabel('READY_WITH_ISSUES')).toContain('待处理问题')
    expect(versionSourceLabel('AI_PARTIAL_REPAIR')).toBe('AI 局部修复')
    expect(priorityLabel('URGENT')).toBe('紧急')
  })

  it('never exposes an unknown internal code', () => {
    expect(issueLabel('SOME_NEW_INTERNAL_CODE')).toBe('规划数据需要检查')
    expect(eventLabel('SOME_NEW_INTERNAL_EVENT')).toBe('规划状态已更新')
  })
})
