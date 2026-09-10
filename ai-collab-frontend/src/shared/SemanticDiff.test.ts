// @vitest-environment jsdom
import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import SemanticDiff from './SemanticDiff.vue'

const taskUpdate = {
  operation: 'UPDATE',
  before: { id: 't1', projectId: 'p', title: '联调', status: 'IN_PROGRESS', priority: 'HIGH', assigneeId: null, dueDate: '2026-09-12', version: 3 },
  after: { status: 'DONE', dueDate: '2026-09-10' },
}
const taskCreate = {
  operation: 'CREATE',
  after: { title: '新任务', status: 'TODO', priority: 'MEDIUM', description: 'desc', id: 't2', version: 0 },
}
const milestoneUpdate = {
  operation: 'UPDATE',
  before: { id: 'm1', name: '里程碑一', status: 'ACTIVE', targetDate: '2026-10-01' },
  after: { id: 'm1', name: '里程碑一', status: 'COMPLETED', targetDate: '2026-10-01' },
}

function textOf(wrapper: ReturnType<typeof mount>): string {
  const rows = wrapper.findAll('.semantic-diff__row').map((r) => r.text().replace(/\s+/g, ' '))
  return rows.join(' | ')
}

describe('SemanticDiff real shapes', () => {
  it('task UPDATE shows only changed fields in Chinese', () => {
    const w = mount(SemanticDiff, { props: { diff: taskUpdate } })
    const t = textOf(w)
    expect(t).toContain('状态')
    expect(t).toContain('进行中')
    expect(t).toContain('已完成')
    expect(t).toContain('截止日期')
    expect(t).toContain('09/12')
    expect(t).toContain('09/10')
    expect(t).not.toContain('version')
    expect(t).not.toContain('projectId')
    expect(t).not.toContain('标题')
  })
  it('task CREATE shows after fields with unset before', () => {
    const w = mount(SemanticDiff, { props: { diff: taskCreate } })
    const t = textOf(w)
    expect(t).toContain('标题')
    expect(t).toContain('新任务')
    expect(t).toContain('未设置')
    expect(t).not.toContain('[object Object]')
  })
  it('milestone UPDATE hides unchanged name and target', () => {
    const w = mount(SemanticDiff, { props: { diff: milestoneUpdate } })
    const t = textOf(w)
    expect(t).toContain('状态')
    expect(t).not.toContain('里程碑一')
    expect(t).not.toContain('目标日期')
  })
  it('never renders [object Object] for nested values', () => {
    const w = mount(SemanticDiff, { props: { diff: { operation: 'CREATE', after: { title: 'x', meta: { a: 1, b: 2 } } } } })
    expect(textOf(w)).not.toContain('[object Object]')
  })
})
