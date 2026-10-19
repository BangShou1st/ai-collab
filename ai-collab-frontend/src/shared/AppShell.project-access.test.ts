// @vitest-environment jsdom
import { flushPromises, mount } from '@vue/test-utils'
import { reactive } from 'vue'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import AppShell from './AppShell.vue'

type ProjectContextMock = {
  project: { name: string } | null
  accessDeniedProjectId: string | null
  isAdminOrOwner: boolean
  clear: ReturnType<typeof vi.fn>
  loadProject: ReturnType<typeof vi.fn>
}

const mocks = vi.hoisted(() => ({
  context: null as ProjectContextMock | null,
  showApiError: vi.fn(),
  push: vi.fn(),
  replace: vi.fn(),
}))

vi.mock('vue-router', () => ({
  useRoute: () => ({ params: { projectId: 'private-project' }, fullPath: '/projects/private-project/dashboard' }),
  useRouter: () => ({ push: mocks.push, replace: mocks.replace }),
}))
vi.mock('element-plus', () => ({ ElMessageBox: { confirm: vi.fn() } }))
vi.mock('../api/api-result', () => ({ showApiError: mocks.showApiError }))
vi.mock('../stores/auth-store', () => ({
  useAuthStore: () => ({ currentUser: { displayName: '测试成员', username: 'member' }, logout: vi.fn() }),
}))
vi.mock('../stores/project-context-store', () => ({
  useProjectContextStore: () => mocks.context!,
}))

const stubs = {
  RouterLink: { template: '<a><slot /></a>' },
  ElIcon: { template: '<span><slot /></span>' },
  ElDropdown: { template: '<div><slot /><slot name="dropdown" /></div>' },
  ElDropdownMenu: { template: '<div><slot /></div>' },
  ElDropdownItem: { template: '<div><slot /></div>' },
}

describe('AppShell project access handling', () => {
  beforeEach(() => {
    mocks.context = reactive({
      project: null,
      accessDeniedProjectId: null,
      isAdminOrOwner: false,
      clear: vi.fn(),
      loadProject: vi.fn(),
    })
    mocks.showApiError.mockReset()
    mocks.push.mockReset()
    mocks.replace.mockReset()
  })

  it('handles project watcher failures instead of leaving a rejected promise', async () => {
    const failure = new Error('network unavailable')
    mocks.context!.loadProject.mockRejectedValue(failure)

    mount(AppShell, { slots: { default: '<div>project content</div>' }, global: { stubs } })
    await flushPromises()

    expect(mocks.context!.loadProject).toHaveBeenCalledWith('private-project')
    expect(mocks.showApiError).toHaveBeenCalledWith(failure, '加载项目')
  })

  it('shows a neutral access-denied page and hides the protected route content', () => {
    mocks.context!.accessDeniedProjectId = 'private-project'
    mocks.context!.project = null

    const wrapper = mount(AppShell, {
      slots: { default: '<div data-test="protected-content">private content</div>' },
      global: { stubs },
    })

    expect(wrapper.get('[role="alert"]').text()).toContain('项目不存在或无权访问')
    expect(wrapper.find('[data-test="protected-content"]').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('私有项目名称')
  })
})
