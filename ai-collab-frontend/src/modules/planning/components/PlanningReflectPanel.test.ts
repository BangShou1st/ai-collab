// @vitest-environment jsdom
import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import PlanningReflectPanel from './PlanningReflectPanel.vue'
import type { ReflectIssue } from '../types'

function makeIssue(severity: 'HARD' | 'SOFT', targetTempKey: string | null = 't1', field: string | null = 'startDate'): ReflectIssue {
  return {
    severity,
    targetType: targetTempKey ? 'TASK' : 'PLAN',
    targetTempKey,
    field,
    suggestion: '建议将截止日期推迟两天以平衡成员负载',
  }
}

describe('PlanningReflectPanel', () => {
  it('displays total count, sorts HARD before SOFT, and caps display at 3', () => {
    const issues = [
      makeIssue('SOFT', 't1'),
      makeIssue('HARD', 't2'),
      makeIssue('SOFT', 't3'),
      makeIssue('HARD', 't4'),
    ]

    const wrapper = mount(PlanningReflectPanel, {
      props: { issues, targetNames: { t2: '任务2', t4: '任务4' } },
    })

    expect(wrapper.get('[data-test="reflect-panel-title"]').text()).toContain('AI 自评建议 · 共 4 条')
    const items = wrapper.findAll('.issue-item')
    expect(items).toHaveLength(3)
    expect(items[0]!.attributes('data-severity')).toBe('HARD')
    expect(items[1]!.attributes('data-severity')).toBe('HARD')
    expect(items[2]!.attributes('data-severity')).toBe('SOFT')
    expect(wrapper.find('[data-test="expand-reflect-issues"]').text()).toContain('展开全部 4 条')
  })

  it('renders PLAN-level issues without locate affordance and shows suggestion text', () => {
    const wrapper = mount(PlanningReflectPanel, {
      props: { issues: [makeIssue('SOFT', null, null)], targetNames: {} },
    })

    const contents = wrapper.findAll('.issue-content')
    expect(contents).toHaveLength(1)
    // PLAN 级问题没有 targetTempKey，不提供定位交互
    expect(contents[0]!.attributes('role')).toBeUndefined()
    expect(wrapper.get('.issue-message').text()).toContain('规划：建议将截止日期推迟两天')
  })

  it('emits locate with targetTempKey and field when clicking a locatable issue', async () => {
    const wrapper = mount(PlanningReflectPanel, {
      props: { issues: [makeIssue('HARD', 't1', 'dueDate')], targetNames: { t1: '任务1' } },
    })

    await wrapper.get('.issue-content[role="button"]').trigger('click')

    expect(wrapper.emitted('locate')?.[0]).toEqual(['t1', 'dueDate'])
  })

  it('expands all issues and collapses back', async () => {
    const issues = Array.from({ length: 5 }, (_, index) =>
      makeIssue(index < 2 ? 'HARD' : 'SOFT', `t${index + 1}`),
    )
    const wrapper = mount(PlanningReflectPanel, {
      props: { issues, targetNames: {} },
    })

    await wrapper.get('[data-test="expand-reflect-issues"]').trigger('click')
    expect(wrapper.findAll('.issue-item')).toHaveLength(5)

    await wrapper.get('[data-test="collapse-reflect-issues"]').trigger('click')
    expect(wrapper.findAll('.issue-item')).toHaveLength(3)
  })
})
