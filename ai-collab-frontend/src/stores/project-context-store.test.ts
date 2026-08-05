import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { projectApi } from '../modules/project/project-api'
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
})
