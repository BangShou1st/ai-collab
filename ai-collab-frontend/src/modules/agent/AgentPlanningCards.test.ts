// @vitest-environment jsdom
import { mount, flushPromises } from '@vue/test-utils'
import { describe, it, expect, vi, afterEach } from 'vitest'
import AgentPlanningCards from './AgentPlanningCards.vue'
import { httpClient } from '../../api/http-client'
vi.mock('../../api/http-client', () => ({ httpClient: { get: vi.fn() } }))
vi.mock('../../api/api-result', () => ({ apiResultFromResponse: (v: unknown) => v, showApiError: vi.fn() }))
const operation = { operationId: 'op', planId: 'plan', title: '规划草稿', status: 'ACCEPTED', versionNo: 0, goalRevision: 2, reviewPath: '/projects/p/ai-planning?planId=plan' }
describe('planning operation cards', () => {
  it('keeps pending cards after a failed poll and resumes until completion', async () => {
    vi.useFakeTimers()
    vi.mocked(httpClient.get).mockResolvedValueOnce({ data: [operation] } as never).mockRejectedValueOnce(new Error('offline')).mockResolvedValueOnce({ data: [{ ...operation, status: 'READY', versionNo: 2 }] } as never)
    const wrapper = mount(AgentPlanningCards, { props: { projectId: 'p', sessionId: 's' }, global: { stubs: { 'router-link': true } } })
    await flushPromises(); await vi.advanceTimersByTimeAsync(5000); await flushPromises()
    expect(wrapper.text()).toContain('规划草稿'); expect(wrapper.text()).toContain('后台处理中')
    await vi.advanceTimersByTimeAsync(5000); await flushPromises()
    expect(wrapper.text()).toContain('版本 2'); expect(wrapper.text()).not.toContain('后台处理中')
    await vi.advanceTimersByTimeAsync(60000); expect(httpClient.get).toHaveBeenCalledTimes(3); wrapper.unmount()
  })
  it('bounds backoff, keeps cards and permits manual retry', async () => {
    vi.useFakeTimers(); vi.mocked(httpClient.get).mockResolvedValueOnce({ data: [operation] } as never).mockRejectedValue(new Error('offline'))
    const wrapper = mount(AgentPlanningCards, { props: { projectId: 'p', sessionId: 's' }, global: { stubs: { 'router-link': true } } })
    await flushPromises(); await vi.advanceTimersByTimeAsync(40000); await flushPromises()
    expect(httpClient.get).toHaveBeenCalledTimes(5); expect(wrapper.text()).toContain('状态更新失败'); expect(wrapper.text()).toContain('规划草稿')
    await vi.advanceTimersByTimeAsync(60000); expect(httpClient.get).toHaveBeenCalledTimes(5)
    vi.mocked(httpClient.get).mockResolvedValue({ data: [{ ...operation, status: 'READY', versionNo: 2 }] } as never)
    await wrapper.get('button').trigger('click'); await flushPromises()
    expect(wrapper.text()).not.toContain('状态更新失败'); expect(wrapper.text()).toContain('版本 2'); wrapper.unmount()
  })
  it('stops retrying when unmounted', async () => {
    vi.useFakeTimers(); vi.mocked(httpClient.get).mockRejectedValue(new Error('offline'))
    const wrapper = mount(AgentPlanningCards, { props: { projectId: 'p', sessionId: 's' } }); await flushPromises(); wrapper.unmount()
    await vi.advanceTimersByTimeAsync(60000); expect(httpClient.get).toHaveBeenCalledTimes(1)
  })
  afterEach(() => { vi.clearAllMocks(); vi.useRealTimers() })
  it('shows accepted as pending and polls the operation endpoint only', async () => {
    vi.useFakeTimers(); vi.mocked(httpClient.get).mockResolvedValue({ data: [operation] } as never)
    const wrapper = mount(AgentPlanningCards, { props: { projectId: 'p', sessionId: 's' }, global: { stubs: { 'router-link': true } } })
    await flushPromises(); expect(wrapper.text()).toContain('已受理'); expect(wrapper.text()).toContain('尚未生成')
    await vi.advanceTimersByTimeAsync(5000); await flushPromises()
    expect(httpClient.get).toHaveBeenCalledTimes(2)
    expect(httpClient.get).toHaveBeenLastCalledWith('/projects/p/agent/sessions/s/planning-operations')
    wrapper.unmount()
  })
  it('ignores an old session response after changing target', async () => {
    let resolveOld!: (value: never) => void
    vi.mocked(httpClient.get).mockReturnValueOnce(new Promise(resolve => { resolveOld = resolve }) as never).mockResolvedValueOnce({ data: [] } as never)
    const wrapper = mount(AgentPlanningCards, { props: { projectId: 'p', sessionId: 'old' } })
    await wrapper.setProps({ sessionId: 'new' }); await flushPromises()
    resolveOld({ data: [operation] } as never); await flushPromises()
    expect(wrapper.text()).not.toContain('规划草稿'); wrapper.unmount()
  })
})
