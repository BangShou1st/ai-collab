// @vitest-environment jsdom
import { defineComponent } from 'vue'
import { mount, flushPromises } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useTaskBoardView } from './use-task-board-view'
import { workApi } from './work-api'
import { projectApi } from '../project/project-api'
import type { Task } from './types'

vi.mock('../../stores/auth-store', () => ({ useAuthStore: () => ({ currentUser: { id: 'member' } }) }))
vi.mock('./work-api', () => ({ workApi: { tasks: vi.fn(), task: vi.fn(), comments: vi.fn(), milestones: vi.fn(), createComment: vi.fn(), updateStatus: vi.fn() } }))
vi.mock('../project/project-api', () => ({ projectApi: { get: vi.fn(), listMembers: vi.fn() } }))
vi.mock('element-plus', () => ({ ElMessage: { success: vi.fn(), warning: vi.fn() }, ElMessageBox: {} }))
vi.mock('../../api/api-result', () => ({ showApiError: vi.fn(), normalizeApiError: vi.fn() }))

const A = '11111111-1111-4111-8111-111111111111'
const B = '22222222-2222-4222-8222-222222222222'
const task = (id: string, projectId = 'p1'): Task => ({ id, projectId, title: id, status: 'TODO', dependencyIds: [], assigneeId: 'member', description: '', milestoneId: null, milestoneName: null, assigneeDisplayName: 'Member', priority: 'MEDIUM', estimateHours: null, startDate: null, dueDate: null, version: 0, unfinishedDependencyCount: 0, sourcePlanId: null })
function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (reason: unknown) => void
  const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no })
  return { promise, resolve, reject }
}
const cleanups: Array<() => void> = []
async function setup(url = '/projects/p1/board') {
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/projects/:projectId/board', component: { template: '<div />' } }] })
  await router.push(url)
  await router.isReady()
  let board!: ReturnType<typeof useTaskBoardView>
  const wrapper = mount(defineComponent({ setup() { board = useTaskBoardView(); return () => null } }), { global: { plugins: [router] } })
  cleanups.push(() => wrapper.unmount())
  await flushPromises()
  return { board, router, wrapper }
}
beforeEach(() => {
  vi.resetAllMocks()
  vi.mocked(workApi.tasks).mockResolvedValue({ data: [] } as never)
  vi.mocked(workApi.milestones).mockResolvedValue({ data: [] } as never)
  vi.mocked(projectApi.get).mockImplementation(async id => ({ data: { id, role: 'MEMBER' } }) as never)
  vi.mocked(projectApi.listMembers).mockResolvedValue({ data: [] } as never)
  vi.mocked(workApi.task).mockImplementation(async (projectId, id) => ({ data: task(id, projectId) }) as never)
  vi.mocked(workApi.comments).mockResolvedValue({ data: [] } as never)
})
afterEach(() => { cleanups.splice(0).forEach(cleanup => cleanup()) })

describe('task URL state with the real router', () => {
  it('initializes an out-of-list detail, including comments, without widening member permissions', async () => {
    const { board } = await setup(`/projects/p1/board?taskId=${A}`)
    expect(board.tasks.value).toEqual([])
    expect(board.selected.value?.id).toBe(A)
    expect(board.drawerVisible.value).toBe(true)
    expect(board.canManage.value).toBe(false)
    expect(workApi.task).toHaveBeenCalledExactlyOnceWith('p1', A)
    expect(workApi.comments).toHaveBeenCalledExactlyOnceWith('p1', A)
  })
  it('does not open without a task ID; normal cards, query changes and history stay synchronized', async () => {
    const { board, router } = await setup()
    expect(board.drawerVisible.value).toBe(false)
    expect(workApi.task).not.toHaveBeenCalled()
    await board.openTask(task(A)); await flushPromises()
    expect(router.currentRoute.value.query.taskId).toBe(A)
    await router.push({ query: { taskId: B } }); await flushPromises()
    expect(board.selected.value?.id).toBe(B)
    router.back(); await flushPromises()
    expect(board.selected.value?.id).toBe(A)
    router.forward(); await flushPromises()
    expect(board.selected.value?.id).toBe(B)
    expect(workApi.task).toHaveBeenCalledTimes(4)
  })
  it('closing preserves other queries; history can reopen and a remount restores the URL', async () => {
    const { board, router, wrapper } = await setup(`/projects/p1/board?taskId=${A}&sourcePlanId=plan&extra=ok`)
    board.drawerVisible.value = false
    await flushPromises()
    expect(router.currentRoute.value.query).toEqual({ sourcePlanId: 'plan', extra: 'ok' })
    expect(board.selected.value).toBeNull()
    router.back(); await flushPromises()
    expect(board.selected.value?.id).toBe(A)
    const url = router.currentRoute.value.fullPath
    wrapper.unmount()
    const refreshed = await setup(url)
    expect(refreshed.board.selected.value?.id).toBe(A)
  })
  it.each(['bad', '', `${A}&taskId=${B}`])('rejects malformed/empty/repeated IDs without a request: %s', async value => {
    const { board } = await setup(`/projects/p1/board?taskId=${value}`)
    expect(board.selected.value).toBeNull()
    expect(board.detailError.value).toContain('任务不存在或无权访问')
    expect(workApi.task).not.toHaveBeenCalled()
  })
  it.each([403, 404])('clears prior content on denied/missing/deleted/cross-project targets (%s)', async status => {
    const { board, router } = await setup(`/projects/p1/board?taskId=${A}`)
    vi.mocked(workApi.task).mockRejectedValueOnce({ status })
    await router.push({ query: { taskId: B } }); await flushPromises()
    expect(board.selected.value).toBeNull()
    expect(board.comments.value).toEqual([])
    expect(board.detailError.value).toContain('任务不存在或无权访问')
    expect(workApi.task).toHaveBeenCalledTimes(2)
  })
  it('does not reveal a title if comments access is denied', async () => {
    vi.mocked(workApi.comments).mockRejectedValueOnce({ status: 403 })
    const { board } = await setup(`/projects/p1/board?taskId=${A}`)
    expect(board.selected.value).toBeNull()
    expect(board.detailError.value).toBeTruthy()
  })
  it('ignores late task responses after switching A to B', async () => {
    const late = deferred<never>()
    vi.mocked(workApi.task).mockReturnValueOnce(late.promise)
    const { board, router } = await setup(`/projects/p1/board?taskId=${A}`)
    await router.push({ query: { taskId: B } }); await flushPromises()
    late.resolve({ data: task(A) } as never); await flushPromises()
    expect(board.selected.value?.id).toBe(B)
    expect(workApi.comments).not.toHaveBeenCalledWith('p1', A)
  })
  it.each(['close', 'unmount', 'project'])('ignores late comments after %s', async action => {
    const late = deferred<never>()
    vi.mocked(workApi.comments).mockReturnValueOnce(late.promise)
    const { board, router, wrapper } = await setup(`/projects/p1/board?taskId=${A}`)
    if (action === 'close') board.drawerVisible.value = false
    if (action === 'unmount') wrapper.unmount()
    if (action === 'project') await router.push(`/projects/p2/board?taskId=${B}`)
    await flushPromises()
    late.resolve({ data: [{ content: 'old protected comment' }] } as never); await flushPromises()
    expect(board.comments.value).toEqual([])
    if (action === 'project') {
      expect(board.selected.value?.projectId).toBe('p2')
      expect(board.project.value?.id).toBe('p2')
      expect(workApi.task).toHaveBeenLastCalledWith('p2', B)
    } else expect(board.selected.value).toBeNull()
  })
  it('does not refetch details for unrelated query or filter changes', async () => {
    const { board, router } = await setup(`/projects/p1/board?taskId=${A}`)
    await router.push({ query: { taskId: A, sourcePlanId: 'plan' } }); await flushPromises()
    board.filters.assigneeId = 'other'
    await flushPromises()
    expect(workApi.task).toHaveBeenCalledTimes(1)
    expect(board.selected.value?.id).toBe(A)
  })
  it('clears protected details when a status refresh discovers revoked access', async () => {
    const { board } = await setup(`/projects/p1/board?taskId=${A}`)
    vi.mocked(workApi.task).mockRejectedValue({ status: 403 })
    await board.updateStatus(task(A), 'IN_PROGRESS')
    expect(board.selected.value).toBeNull()
    expect(board.comments.value).toEqual([])
    expect(board.detailError.value).toContain('任务不存在或无权访问')
  })
  it('does not let a late mutation refresh replace a new detail', async () => {
    const { board, router } = await setup(`/projects/p1/board?taskId=${A}`)
    const late = deferred<never>()
    vi.mocked(workApi.updateStatus).mockReturnValueOnce(late.promise)
    const saving = board.updateStatus(task(A), 'IN_PROGRESS')
    await router.push({ query: { taskId: B } }); await flushPromises()
    late.resolve({ data: task(A) } as never)
    await saving; await flushPromises()
    expect(board.selected.value?.id).toBe(B)
    expect(workApi.task).toHaveBeenCalledTimes(2)
  })
  it('ignores old board/list responses after a project switch', async () => {
    const late = deferred<never>()
    vi.mocked(workApi.tasks).mockReturnValueOnce(late.promise)
    const { board, router } = await setup()
    await router.push('/projects/p2/board'); await flushPromises()
    late.resolve({ data: [task(A)] } as never); await flushPromises()
    expect(board.tasks.value).toEqual([])
    expect(board.project.value?.id).toBe('p2')
  })
})
