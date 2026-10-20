// @vitest-environment jsdom
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { describe, expect, it, vi } from 'vitest'
import HomeView from './HomeView.vue'
import { homeApi } from '../../api/home-api'

vi.mock('../../stores/auth-store', () => ({ useAuthStore: () => ({ currentUser: null }) }))
vi.mock('../../api/home-api', () => ({ homeApi: { get: vi.fn() } }))

async function render(count: number) {
  vi.mocked(homeApi.get).mockResolvedValue({ data: {
    recentProjects: [], recentActivity: [], pendingApprovals: 0,
    notificationsSummary: { unread: 0 }, aiConfigSummary: { configured: true, providerCount: 1 },
    myTasks: Array.from({ length: count }, (_, index) => ({ id: `task-${index}`, projectId: `project-${index}`, projectName: `项目 ${index}`, title: `待办 ${index}`, status: 'BLOCKED', dueDate: index ? '2026-10-30' : null })),
  } } as never)
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/:pathMatch(.*)*', component: HomeView }] })
  await router.push('/home')
  const wrapper = mount(HomeView, { global: { plugins: [router], directives: { loading: () => {} }, stubs: { ElButton: true, ElIcon: true, ElTag: { template: '<span><slot /></span>' } } } })
  await flushPromises()
  return wrapper
}
describe('home task preview', () => {
  it('exposes five native links with projects, real status and due date fallback, without a total count', async () => {
    const wrapper = await render(8)
    const links = wrapper.findAll('a.home-task-link')
    expect(links).toHaveLength(5)
    expect(links[0]!.attributes('href')).toBe('/projects/project-0/board?taskId=task-0')
    expect(links[0]!.text()).toContain('项目 0')
    expect(links[0]!.text()).toContain('已阻塞')
    expect(links[0]!.text()).toContain('未设置截止日期')
    expect(links[1]!.text()).toContain('截止日期：')
    expect(wrapper.text()).toContain('我的待办')
    expect(wrapper.text()).toContain('最多展示 5 项')
    expect(wrapper.text()).not.toContain('8 个')
    wrapper.unmount()
  })
  it('retains an understandable empty state', async () => {
    const wrapper = await render(0)
    expect(wrapper.text()).toContain('暂无待办任务')
    expect(wrapper.findAll('a.home-task-link')).toHaveLength(0)
    wrapper.unmount()
  })
})
