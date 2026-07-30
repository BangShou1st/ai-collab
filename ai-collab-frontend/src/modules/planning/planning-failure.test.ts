import { describe, expect, it } from 'vitest'
import { planningFailureLabel } from './planning-failure'

describe('planningFailureLabel', () => {
  it.each([
    ['SKELETON / MODEL_OUTPUT_INVALID / milestones[0]', '规划骨架生成结果无效'],
    ['DETAIL / DOMAIN_VALIDATION_FAILED / tasks[0]', '任务细节未通过业务规则校验'],
    ['REPAIR / PROVIDER_ERROR / upstream', '规划修复模型服务暂时不可用'],
  ])('maps safe stage and code without exposing internals', (summary, expected) => {
    const label = planningFailureLabel(summary)
    expect(label).toBe(expected)
    expect(label).not.toMatch(/[A-Z_]{3,}/)
    expect(label).not.toContain('[0]')
  })

  it('uses a safe Chinese fallback for unknown summaries', () => {
    const label = planningFailureLabel('INTERNAL_SQL_ERROR / secret=abc')
    expect(label).toBe('系统暂时无法处理该规划，请稍后重试')
    expect(label).not.toContain('SQL')
    expect(label).not.toContain('secret')
  })

  it('returns an empty label when there is no failure', () => {
    expect(planningFailureLabel(null)).toBe('')
  })
})
