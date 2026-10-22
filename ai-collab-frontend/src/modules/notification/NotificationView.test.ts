// @vitest-environment jsdom
import { defineComponent } from 'vue'
import { mount, flushPromises } from '@vue/test-utils'
import { createMemoryHistory, createRouter, RouterView } from 'vue-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import NotificationView from './NotificationView.vue'
import { notificationTaskRoute } from './notification-task-route'
import { notificationApi } from './notification-api'
import { showApiError } from '../../api/api-result'
import { ElMessage } from 'element-plus'
import type { Notification } from './types'
vi.mock('./notification-api', () => ({ notificationApi: { list: vi.fn(), markAsRead: vi.fn(), markAllAsRead: vi.fn() } }))
vi.mock('../../api/api-result', () => ({ showApiError: vi.fn() }))
vi.mock('element-plus', () => ({ ElMessage: { success: vi.fn() } }))
const P = '11111111-1111-4111-8111-111111111111'
const T = '22222222-2222-4222-8222-222222222222'
const item = (overrides: Partial<Notification> = {}): Notification => ({ id: 'n1', projectId: P, projectName: '合成项目', type: 'TASK_ASSIGNED', title: '合成通知', content: 'https://untrusted.example', entityType: 'TASK', entityId: T, read: false, createdAt: '2026-10-20T00:00:00Z', ...overrides })
function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (error: unknown) => void
  const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no })
  return { promise, resolve, reject }
}
const cleanups: Array<() => void> = []
async function render(items = [item()]) {
  vi.mocked(notificationApi.list).mockResolvedValue({ data: items } as never)
  const router = createRouter({ history: createMemoryHistory(), routes: [
    { path: '/notifications', component: NotificationView },
    { path: '/projects/:projectId/board', component: { template: '<div>任务看板</div>' } },
    { path: '/home', component: { template: '<div>工作台</div>' } },
  ] })
  await router.push('/notifications')
  const wrapper = mount(defineComponent({ components: { RouterView }, template: '<RouterView />' }), { global: { plugins: [router], directives: { loading: () => {} }, stubs: {
    PageHeader: { template: '<header><slot name="actions" /></header>' },
    ElButton: { props: ['disabled'], template: '<button :disabled="disabled"><slot /></button>' },
    ElTag: { template: '<span><slot /></span>' }, ElEmpty: true,
  } } })
  cleanups.push(() => wrapper.unmount())
  await flushPromises()
  const button = (text: string) => wrapper.findAll('button').find(b => b.text() === text)!
  return { wrapper, router, button, items }
}
beforeEach(() => { vi.resetAllMocks() })
afterEach(() => { cleanups.splice(0).forEach(cleanup => cleanup()) })
describe('notification destination whitelist', () => {
  it('constructs the approved internal route independently of content and notification type', () => {
    expect(notificationTaskRoute(item())).toEqual({ path: `/projects/${P}/board`, query: { taskId: T } })
    expect(notificationTaskRoute(item({ type: 'COMMENT_ADDED' }))).toEqual(notificationTaskRoute(item()))
  })
  it.each([{ entityType: 'DOCUMENT' }, { entityType: 'task' }, { entityType: null }, { entityType: '../TASK' }, { entityId: null }, { entityId: '' }, { entityId: 'https://evil.example' }, { entityId: `${T}?x=1` }, { projectId: '../other' }, { projectId: '' }, { projectId: `${P}/board` }])('rejects %j', value => { expect(notificationTaskRoute(item(value))).toBeNull() })
})
describe('notification operations with real router', () => {
  it('awaits read, blocks duplicate/all operations, then navigates', async () => {
    const pending = deferred<void>(); vi.mocked(notificationApi.markAsRead).mockReturnValue(pending.promise)
    const { router, button, items } = await render()
    await button('标为已读并打开任务').trigger('click'); await button('标为已读并打开任务').trigger('click'); await button('全部已读').trigger('click')
    expect(router.currentRoute.value.path).toBe('/notifications'); expect(items[0]!.read).toBe(false)
    expect(notificationApi.markAsRead).toHaveBeenCalledExactlyOnceWith('n1'); expect(notificationApi.markAllAsRead).not.toHaveBeenCalled()
    pending.resolve(); await flushPromises()
    expect(items[0]!.read).toBe(true); expect(router.currentRoute.value.fullPath).toBe(`/projects/${P}/board?taskId=${T}`)
  })
  it('opens read notifications repeatedly through history without another write', async () => {
    const { router, button } = await render([item({ read: true })])
    await button('打开任务').trigger('click'); await flushPromises(); router.back(); await flushPromises()
    await button('打开任务').trigger('click'); await flushPromises()
    expect(router.currentRoute.value.query.taskId).toBe(T); expect(notificationApi.markAsRead).not.toHaveBeenCalled()
  })
  it('keeps unread and route on read failure, reports error and permits retry', async () => {
    vi.mocked(notificationApi.markAsRead).mockRejectedValueOnce(new Error('failed')).mockResolvedValueOnce()
    const { router, button, items } = await render()
    await button('标为已读并打开任务').trigger('click'); await flushPromises()
    expect(items[0]!.read).toBe(false); expect(router.currentRoute.value.path).toBe('/notifications')
    expect(showApiError).toHaveBeenCalledWith(expect.any(Error), '通知标记已读'); expect(ElMessage.success).not.toHaveBeenCalled()
    await button('标为已读并打开任务').trigger('click'); await flushPromises(); expect(router.currentRoute.value.query.taskId).toBe(T)
  })
  it.each([{ entityType: 'PLAN' }, { entityId: null }, { projectId: 'bad' }])('retains read-only operation for %j', async overrides => {
    const { wrapper, router, button, items } = await render([item(overrides)])
    expect(wrapper.text()).not.toContain('打开任务'); await button('标为已读').trigger('click'); await flushPromises()
    expect(items[0]!.read).toBe(true); expect(router.currentRoute.value.path).toBe('/notifications')
  })
  it.each(['resolve', 'reject'])('ignores late read %s after leaving and returning', async outcome => {
    const late = deferred<void>(); vi.mocked(notificationApi.markAsRead).mockReturnValue(late.promise)
    const { router, button, items } = await render()
    await button('标为已读并打开任务').trigger('click'); await router.push('/home'); await flushPromises()
    vi.mocked(notificationApi.list).mockResolvedValue({ data: [item()] } as never)
    router.back(); await flushPromises()
    if (outcome === 'resolve') late.resolve(); else late.reject(new Error('late'))
    await flushPromises(); expect(router.currentRoute.value.path).toBe('/notifications'); expect(items[0]!.read).toBe(false); expect(showApiError).not.toHaveBeenCalled()
  })
  it('invalidates pending navigation on a same-component query change', async () => {
    const late = deferred<void>(); vi.mocked(notificationApi.markAsRead).mockReturnValue(late.promise)
    const { router, button } = await render(); await button('标为已读并打开任务').trigger('click')
    await router.push('/notifications?x=1'); await flushPromises(); late.resolve(); await flushPromises()
    expect(router.currentRoute.value.fullPath).toBe('/notifications?x=1'); expect(button('标为已读并打开任务').attributes('disabled')).toBeUndefined()
  })
  it('serializes paging and read-all and retains all loaded rows', async () => {
    const { wrapper, button } = await render(Array.from({ length: 20 }, (_, i) => item({ id: `n${i}` })))
    const pending = deferred<never>(); vi.mocked(notificationApi.list).mockReturnValueOnce(pending.promise)
    await button('加载更多').trigger('click'); await button('加载更多').trigger('click'); await button('全部已读').trigger('click')
    expect(notificationApi.list).toHaveBeenCalledTimes(2); expect(notificationApi.list).toHaveBeenLastCalledWith(1, 20); expect(notificationApi.markAllAsRead).not.toHaveBeenCalled()
    pending.resolve({ data: [item({ id: 'last' })] } as never); await flushPromises()
    expect(wrapper.findAll('.notification-item')).toHaveLength(21); expect(wrapper.text()).not.toContain('加载更多')
    await button('全部已读').trigger('click'); await flushPromises()
    expect(wrapper.findAll('.unread')).toHaveLength(0); expect(ElMessage.success).toHaveBeenCalledExactlyOnceWith('已全部标记为已读')
    expect(wrapper.findAll('button').filter(b => b.text() === '打开任务')).toHaveLength(21)
  })
  it('retains unread on read-all failure and permits retry', async () => {
    vi.mocked(notificationApi.markAllAsRead).mockRejectedValueOnce(new Error('failed'))
    const { wrapper, button } = await render(); await button('全部已读').trigger('click'); await flushPromises()
    expect(wrapper.findAll('.unread')).toHaveLength(1); expect(ElMessage.success).not.toHaveBeenCalled(); expect(showApiError).toHaveBeenCalledWith(expect.any(Error), '全部通知标记已读')
    await button('全部已读').trigger('click'); await flushPromises(); expect(wrapper.findAll('.unread')).toHaveLength(0)
  })
  it('retries a failed page without skipping', async () => {
    const { wrapper, button } = await render(Array.from({ length: 20 }, (_, i) => item({ id: `n${i}` })))
    vi.mocked(notificationApi.list).mockRejectedValueOnce(new Error('failed')).mockResolvedValueOnce({ data: [] } as never)
    await button('加载更多').trigger('click'); await flushPromises(); expect(wrapper.findAll('.notification-item')).toHaveLength(20)
    await button('加载更多').trigger('click'); await flushPromises()
    expect(notificationApi.list).toHaveBeenNthCalledWith(2, 1, 20); expect(notificationApi.list).toHaveBeenNthCalledWith(3, 1, 20); expect(wrapper.text()).not.toContain('加载更多')
  })
})

describe('notification pending response isolation', () => {
  it.each(['page', 'all'])('ignores late %s success after unmount', async operation => {
    const rows = Array.from({ length: 20 }, (_, i) => item({ id: `n${i}` }))
    const { router, button, items } = await render(rows)
    const pending = deferred<never>()
    if (operation === 'page') vi.mocked(notificationApi.list).mockReturnValueOnce(pending.promise)
    else vi.mocked(notificationApi.markAllAsRead).mockReturnValueOnce(pending.promise)
    await button(operation === 'page' ? '加载更多' : '全部已读').trigger('click')
    await router.push('/home'); await flushPromises()
    pending.resolve({ data: [item({ id: 'late' })] } as never); await flushPromises()
    expect(router.currentRoute.value.path).toBe('/home')
    expect(items.every(n => !n.read)).toBe(true)
    expect(ElMessage.success).not.toHaveBeenCalled()
  })
  it('loads the next page only after read-all finishes and keeps its returned read state', async () => {
    const { wrapper, button } = await render(Array.from({ length: 20 }, (_, i) => item({ id: `n${i}` })))
    const pending = deferred<void>(); vi.mocked(notificationApi.markAllAsRead).mockReturnValueOnce(pending.promise)
    await button('全部已读').trigger('click'); await button('加载更多').trigger('click'); await button('全部已读').trigger('click')
    expect(notificationApi.list).toHaveBeenCalledTimes(1); expect(notificationApi.markAllAsRead).toHaveBeenCalledTimes(1)
    pending.resolve(); await flushPromises()
    // A genuinely new arrival after read-all must remain unread, not be overwritten locally.
    vi.mocked(notificationApi.list).mockResolvedValueOnce({ data: [item({ id: 'new', read: false }), item({ id: 'old', read: true })] } as never)
    await button('加载更多').trigger('click'); await flushPromises()
    expect(wrapper.findAll('.notification-item')).toHaveLength(22)
    expect(wrapper.findAll('.unread')).toHaveLength(1)
  })
})
