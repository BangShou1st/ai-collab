import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { projectApi } from '../modules/project/project-api'
import type { ApiResult } from '../api/types'
import type { Project } from '../modules/project/types'
import { useProjectContextStore } from './project-context-store'

vi.mock('../modules/project/project-api', () => ({
  projectApi: {
    get: vi.fn(),
  },
}))

const project = {
  id: 'project-1',
  name: '竞赛项目',
  description: '',
  type: 'COMPETITION' as const,
  startDate: null,
  dueDate: null,
  status: 'PREPARING' as const,
  version: 0,
  role: 'ADMIN' as const,
}

function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (reason?: unknown) => void
  const promise = new Promise<T>((resolvePromise, rejectPromise) => {
    resolve = resolvePromise
    reject = rejectPromise
  })
  return { promise, resolve, reject }
}

function projectResult(value: Project): ApiResult<Project> {
  return { httpStatus: 200, code: 'SUCCESS', message: '操作成功', data: value }
}

describe('project context store', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.mocked(projectApi.get).mockReset()
  })

  it('loads a project once and derives administrator access', async () => {
    vi.mocked(projectApi.get).mockResolvedValue({
      httpStatus: 200,
      code: 'SUCCESS',
      message: '操作成功',
      data: project,
    })
    const store = useProjectContextStore()

    await store.loadProject('project-1')
    await store.loadProject('project-1')

    expect(store.project).toEqual(project)
    expect(store.isAdminOrOwner).toBe(true)
    expect(projectApi.get).toHaveBeenCalledTimes(1)
  })

  it('clears role and project identity together', async () => {
    vi.mocked(projectApi.get).mockResolvedValue({
      httpStatus: 200,
      code: 'SUCCESS',
      message: '操作成功',
      data: project,
    })
    const store = useProjectContextStore()
    await store.loadProject('project-1')

    store.clear()

    expect(store.currentProjectId).toBeNull()
    expect(store.project).toBeNull()
    expect(store.isAdminOrOwner).toBe(false)
  })

  it('refreshes the cached role after ownership changes', async () => {
    vi.mocked(projectApi.get)
      .mockResolvedValueOnce({ httpStatus: 200, code: 'SUCCESS', message: '操作成功', data: project })
      .mockResolvedValueOnce({
        httpStatus: 200,
        code: 'SUCCESS',
        message: '操作成功',
        data: { ...project, role: 'MEMBER' },
      })
    const store = useProjectContextStore()

    await store.loadProject('project-1')
    await store.refreshProject('project-1')

    expect(projectApi.get).toHaveBeenCalledTimes(2)
    expect(store.currentUserRole).toBe('MEMBER')
  })

  it('turns a project 404 into a denied state and clears stale project data', async () => {
    vi.mocked(projectApi.get)
      .mockResolvedValueOnce({ httpStatus: 200, code: 'SUCCESS', message: '操作成功', data: project })
      .mockRejectedValueOnce(Object.assign(new Error('not found'), {
        isAxiosError: true,
        response: {
          status: 404,
          data: { code: 'PROJECT_NOT_FOUND', message: '项目不存在或你无权访问' },
        },
      }))
    const store = useProjectContextStore()
    await store.loadProject('project-1')

    await expect(store.loadProject('private-project')).resolves.toBeUndefined()

    expect(store.currentProjectId).toBeNull()
    expect(store.project).toBeNull()
    expect(store.accessDeniedProjectId).toBe('private-project')
    expect(store.loading).toBe(false)
  })

  it('does not retry the same denied project until navigation changes', async () => {
    vi.mocked(projectApi.get).mockRejectedValue(Object.assign(new Error('not found'), {
      isAxiosError: true,
      response: { status: 404, data: { code: 'PROJECT_NOT_FOUND' } },
    }))
    const store = useProjectContextStore()

    await store.loadProject('private-project')
    await store.loadProject('private-project')

    expect(projectApi.get).toHaveBeenCalledTimes(1)
    expect(store.accessDeniedProjectId).toBe('private-project')
  })

  it('keeps the latest project and role when A and B requests resolve out of order', async () => {
    const requestA = deferred<ApiResult<Project>>()
    const requestB = deferred<ApiResult<Project>>()
    vi.mocked(projectApi.get).mockImplementation((id) => id === 'project-a'
      ? requestA.promise
      : requestB.promise)
    const store = useProjectContextStore()

    const loadingA = store.loadProject('project-a')
    const loadingB = store.loadProject('project-b')
    requestB.resolve(projectResult({ ...project, id: 'project-b', role: 'MEMBER' }))
    await loadingB
    requestA.resolve(projectResult({ ...project, id: 'project-a', role: 'OWNER' }))
    await loadingA

    expect(store.currentProjectId).toBe('project-b')
    expect(store.project?.id).toBe('project-b')
    expect(store.currentUserRole).toBe('MEMBER')
    expect(store.accessDeniedProjectId).toBeNull()
    expect(store.loading).toBe(false)
  })

  it('does not let a stale permission 404 replace a newer project', async () => {
    const requestA = deferred<ApiResult<Project>>()
    const requestB = deferred<ApiResult<Project>>()
    vi.mocked(projectApi.get).mockImplementation((id) => id === 'private-a'
      ? requestA.promise
      : requestB.promise)
    const store = useProjectContextStore()

    const loadingA = store.loadProject('private-a')
    const loadingB = store.loadProject('project-b')
    requestB.resolve(projectResult({ ...project, id: 'project-b', role: 'MEMBER' }))
    await loadingB
    requestA.reject(Object.assign(new Error('not found'), {
      isAxiosError: true,
      response: { status: 404, data: { code: 'PROJECT_NOT_FOUND' } },
    }))
    await loadingA

    expect(store.currentProjectId).toBe('project-b')
    expect(store.currentUserRole).toBe('MEMBER')
    expect(store.accessDeniedProjectId).toBeNull()
  })

  it('ignores a late project response after clear invalidates the session context', async () => {
    const request = deferred<ApiResult<Project>>()
    vi.mocked(projectApi.get).mockReturnValue(request.promise)
    const store = useProjectContextStore()

    const loading = store.loadProject('project-a')
    store.clear()
    request.resolve(projectResult({ ...project, id: 'project-a', role: 'OWNER' }))
    await loading

    expect(store.currentProjectId).toBeNull()
    expect(store.project).toBeNull()
    expect(store.currentUserRole).toBeNull()
    expect(store.accessDeniedProjectId).toBeNull()
    expect(store.loading).toBe(false)
  })

  it('ignores a stale refresh 404 after navigation to another project', async () => {
    const initial = projectResult({ ...project, id: 'project-a' })
    const refresh = deferred<ApiResult<Project>>()
    const next = deferred<ApiResult<Project>>()
    vi.mocked(projectApi.get)
      .mockResolvedValueOnce(initial)
      .mockReturnValueOnce(refresh.promise)
      .mockReturnValueOnce(next.promise)
    const store = useProjectContextStore()
    await store.loadProject('project-a')

    const refreshingA = store.refreshProject('project-a')
    const loadingB = store.loadProject('project-b')
    next.resolve(projectResult({ ...project, id: 'project-b', role: 'MEMBER' }))
    await loadingB
    refresh.reject(Object.assign(new Error('not found'), {
      isAxiosError: true,
      response: { status: 404, data: { code: 'PROJECT_NOT_FOUND' } },
    }))
    await refreshingA

    expect(store.currentProjectId).toBe('project-b')
    expect(store.currentUserRole).toBe('MEMBER')
    expect(store.accessDeniedProjectId).toBeNull()
  })
})
