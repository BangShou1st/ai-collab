// @vitest-environment jsdom
import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import PlanningIssuePanel from './PlanningIssuePanel.vue'
import type { StructuredValidationIssue } from '../types'

function makeIssue(id: string, severity: 'HARD' | 'BLOCKING_EDITABLE' | 'WARNING', code = 'TEST_CODE', targetTempKey = 't1', field = 'startDate'): StructuredValidationIssue {
  return {
    id,
    code,
    severity,
    targetType: 'TASK',
    targetTempKey,
    field,
    relatedTempKey: null,
    safeDetails: {},
  }
}

describe('PlanningIssuePanel', () => {
  it('displays up to 3 issues by default and shows total count in title', () => {
    const issues = [
      makeIssue('i-1', 'WARNING', 'UNASSIGNED_TASK'),
      makeIssue('i-2', 'BLOCKING_EDITABLE', 'DEPENDENCY_DATE_CONFLICT'),
      makeIssue('i-3', 'HARD', 'ESTIMATE_HOURS_INVALID'),
      makeIssue('i-4', 'WARNING', 'DUPLICATE_TASK_TITLE'),
      makeIssue('i-5', 'BLOCKING_EDITABLE', 'START_DATE_OUT_OF_RANGE'),
    ]

    const wrapper = mount(PlanningIssuePanel, {
      props: {
        issues,
        targetNames: { t1: '任务1' },
        actionsEnabled: true,
      },
    })

    // Title shows total 5
    expect(wrapper.get('[data-test="issue-panel-title"]').text()).toContain('规划校验警告 · 共 5 条')

    // Renders only 3 items initially
    const items = wrapper.findAll('.issue-item')
    expect(items).toHaveLength(3)

    // Sorted by severity: HARD first, then BLOCKING_EDITABLE
    expect(items[0]!.attributes('data-severity')).toBe('HARD')
    expect(items[1]!.attributes('data-severity')).toBe('BLOCKING_EDITABLE')
    expect(items[2]!.attributes('data-severity')).toBe('BLOCKING_EDITABLE')

    // Expand button is displayed with total count
    const expandBtn = wrapper.find('[data-test="expand-all-issues"]')
    expect(expandBtn.exists()).toBe(true)
    expect(expandBtn.text()).toContain('展开全部 5 条')
  })

  it('does not show expand button when issues are 3 or fewer', () => {
    const issues = [
      makeIssue('i-1', 'WARNING'),
      makeIssue('i-2', 'BLOCKING_EDITABLE'),
    ]

    const wrapper = mount(PlanningIssuePanel, {
      props: {
        issues,
        targetNames: { t1: '任务1' },
        actionsEnabled: true,
      },
    })

    expect(wrapper.get('[data-test="issue-panel-title"]').text()).toContain('规划校验警告 · 共 2 条')
    expect(wrapper.findAll('.issue-item')).toHaveLength(2)
    expect(wrapper.find('[data-test="expand-all-issues"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="collapse-issues"]').exists()).toBe(false)
  })

  it('expands all issues on click and allows collapsing back to 3', async () => {
    const issues = Array.from({ length: 6 }, (_, index) =>
      makeIssue(`i-${index + 1}`, index < 2 ? 'HARD' : 'WARNING'),
    )

    const wrapper = mount(PlanningIssuePanel, {
      props: {
        issues,
        targetNames: { t1: '任务1' },
        actionsEnabled: true,
      },
    })

    expect(wrapper.findAll('.issue-item')).toHaveLength(3)

    // Click expand
    await wrapper.get('[data-test="expand-all-issues"]').trigger('click')
    expect(wrapper.findAll('.issue-item')).toHaveLength(6)

    // Button changed to collapse
    const collapseBtn = wrapper.find('[data-test="collapse-issues"]')
    expect(collapseBtn.exists()).toBe(true)
    expect(collapseBtn.text()).toContain('收起警告')

    // Click collapse
    await collapseBtn.trigger('click')
    expect(wrapper.findAll('.issue-item')).toHaveLength(3)
    expect(wrapper.find('[data-test="expand-all-issues"]').exists()).toBe(true)
  })

  it('emits locate on issue click and keyboard Enter/Space', async () => {
    const issues = [makeIssue('i-1', 'BLOCKING_EDITABLE', 'CODE', 'task-target', 'dueDate')]

    const wrapper = mount(PlanningIssuePanel, {
      props: {
        issues,
        targetNames: { 'task-target': '目标任务' },
        actionsEnabled: true,
      },
    })

    const content = wrapper.get('.issue-content')
    await content.trigger('click')
    expect(wrapper.emitted('locate')).toEqual([['task-target', 'dueDate']])

    await content.trigger('keydown', { key: 'Enter' })
    expect(wrapper.emitted('locate')![1]).toEqual(['task-target', 'dueDate'])

    await content.trigger('keydown', { key: ' ' })
    expect(wrapper.emitted('locate')![2]).toEqual(['task-target', 'dueDate'])
  })

  it('emits edit when manual edit button is clicked', async () => {
    const issueItem = makeIssue('i-1', 'BLOCKING_EDITABLE', 'CODE', 'task-target', 'dueDate')

    const wrapper = mount(PlanningIssuePanel, {
      props: {
        issues: [issueItem],
        targetNames: { 'task-target': '目标任务' },
        actionsEnabled: true,
      },
    })

    await wrapper.get('.btn-sm').trigger('click')
    expect(wrapper.emitted('edit')![0]![0]).toEqual(issueItem)
  })
})
