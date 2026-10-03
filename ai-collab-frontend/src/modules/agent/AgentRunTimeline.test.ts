// @vitest-environment jsdom
import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import AgentRunTimeline from './AgentRunTimeline.vue'
import type { AgentRunEvent } from './types'

const event = (sequence: number, type: AgentRunEvent['type'], payload: Record<string, unknown> = {}): AgentRunEvent => ({
  id: `e${sequence}`, projectId: 'p', runId: 'r', sequence, type, payload, createdAt: '2026-10-03T00:00:00Z',
})
const plan = { version: 1, objective: '研究项目', steps: [{ id: '1', title: '搜索知识库', status: 'PENDING' }] }

describe('Agent execution and template presentation', () => {
  it('renders successful task facts independently of model prose and never uses failed results', () => {
    const wrapper = mount(AgentRunTimeline, { props: { plan, status: 'SUCCEEDED', events: [
      event(1, 'TOOL_CALL_COMPLETED', { toolName: 'list_tasks', status: 'SUCCEEDED', taskFacts: [
        { id: 't', title: '核对验收', status: 'TODO', assigneeId: 'u', assigneeName: '张三' },
        { id: 't2', title: '负责人姓名不可用', status: 'IN_PROGRESS', assigneeId: 'u2', assigneeName: null },
      ] }),
      event(2, 'TOOL_CALL_FAILED', { toolName: 'list_tasks', status: 'FAILED', taskFacts: [{ title: '虚假任务', status: 'DONE' }] }),
    ] } })
    const facts = wrapper.get('[aria-label="任务查询事实"]').text()
    expect(facts).toContain('核对验收')
    expect(facts).toContain('待处理（TODO）')
    expect(facts).toContain('张三')
    expect(facts).toContain('已分配（姓名不可用）')
    expect(facts).not.toContain('虚假任务')
  })
  it('keeps unrelated templates as references and shows successful and failed real invocations separately', () => {
    const wrapper = mount(AgentRunTimeline, { props: { plan, status: 'SUCCEEDED', events: [
      event(1, 'TOOL_CALL_STARTED', { invocationId: 'a', toolName: 'list_tasks' }),
      event(2, 'TOOL_CALL_COMPLETED', { invocationId: 'a', toolName: 'list_tasks', success: true }),
      event(3, 'TOOL_CALL_STARTED', { invocationId: 'b', toolName: 'get_task' }),
      event(4, 'TOOL_CALL_FAILED', { invocationId: 'b', toolName: 'get_task', errorCode: 'TOOL_EXECUTION_FAILED' }),
      event(5, 'RUN_SUCCEEDED'),
    ] } })
    expect(wrapper.get('[aria-label="实际工具执行"]').text()).toContain('调用完成')
    expect(wrapper.get('[aria-label="实际工具执行"]').text()).toContain('失败')
    expect(wrapper.get('[aria-label="参考步骤"]').text()).toContain('参考步骤')
    expect(wrapper.get('[aria-label="参考步骤"]').text()).not.toContain('已完成')
    expect(wrapper.text()).not.toContain('待执行')
  })
  it('updates from tool events and does not leave a canceled invocation running', async () => {
    const events = [event(1, 'TOOL_CALL_STARTED', { invocationId: 'a', toolName: 'list_tasks' })]
    const wrapper = mount(AgentRunTimeline, { props: { plan, status: 'RUNNING', events } })
    expect(wrapper.text()).toContain('进行中')
    await wrapper.setProps({ status: 'CANCELED', events: [...events, event(2, 'RUN_CANCELED')] })
    expect(wrapper.text()).toContain('已取消')
    expect(wrapper.text()).not.toContain('进行中')
    expect(wrapper.text()).not.toContain('待执行')
  })
})
