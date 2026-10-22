// @vitest-environment jsdom
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import HomeView from './HomeView.vue'
import { homeApi } from '../../api/home-api'

const authState = vi.hoisted(() => ({
  currentUser: { id: 'user-1', displayName: '测试用户' } as { id: string; displayName: string } | null,
  listeners: [] as Array<() => void>,
}))

vi.mock('../../stores/auth-store', () => ({
  useAuthStore: () => ({
    get currentUser() {
      return authState.currentUser
    },
    $subscribe: (cb: () => void) => {
      authState.listeners.push(cb)
    },
  }),
}))
vi.mock('../../api/home-api', () => ({ homeApi: { get: vi.fn() } }))

const summary = (over: Partial<{ configured: boolean; defaultModel: string | null; providerCount: number; anyPurposeUsable: boolean }> = {}) => ({
  configured: false, defaultModel: null, providerCount: 0, anyPurposeUsable: false, ...over,
})

function payload(over: Partial<ReturnType<typeof summary>> = {}, taskCount = 0) {
  return {
    recentProjects: [], recentActivity: [], pendingApprovals: 0,
    notificationsSummary: { unread: 0 }, aiConfigSummary: summary(over),
    myTasks: Array.from({ length: taskCount }, (_, index) => ({ id: `task-${index}`, projectId: `project-${index}`, projectName: `项目 ${index}`, title: `待办 ${index}`, status: 'BLOCKED', dueDate: index ? '2026-10-30' : null })),
  }
}

const stubs = {
  ElButton: { inheritAttrs: false, template: '<button v-bind="$attrs"><slot /></button>' },
  ElIcon: { template: '<span><slot /></span>' },
  ElTag: { template: '<span><slot /></span>' },
}

async function render(data: unknown = payload({}, 0)) {
  vi.mocked(homeApi.get).mockResolvedValue({ data } as never)
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/:pathMatch(.*)*', component: HomeView }] })
  await router.push('/home')
  const wrapper = mount(HomeView, {
    global: {
      plugins: [router],
      directives: { loading: () => {} },
      stubs,
    },
  })
  await flushPromises()
  return wrapper
}

beforeEach(() => {
  authState.currentUser = { id: 'user-1', displayName: '测试用户' }
  authState.listeners = []
  vi.mocked(homeApi.get).mockReset()
})

describe('home task preview', () => {
  it('exposes five native links with projects, real status and due date fallback, without a total count', async () => {
    const wrapper = await render(payload({}, 8))
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
    const wrapper = await render(payload({}, 0))
    expect(wrapper.text()).toContain('暂无待办任务')
    expect(wrapper.findAll('a.home-task-link')).toHaveLength(0)
    wrapper.unmount()
  })
})

describe('home AI configuration status', () => {
  it('shows onboarding, not a ready claim, when nothing is saved', async () => {
    const wrapper = await render(payload())
    expect(wrapper.text()).toContain('让 AI 加入你的工作流')
    expect(wrapper.text()).toContain('还没有保存任何 AI 模型配置')
    expect(wrapper.text()).not.toContain('AI 已就绪')
    const link = wrapper.findAll('a').find((a) => a.text().includes('配置 AI'))
    expect(link?.attributes('href')).toBe('/settings/ai')
    wrapper.unmount()
  })

  it('never reports "ready" when providers are saved but no purpose resolves', async () => {
    const wrapper = await render(payload({ configured: true, providerCount: 2, anyPurposeUsable: false }))
    expect(wrapper.text()).toContain('AI 配置已保存，但暂不可用')
    expect(wrapper.text()).toContain('已保存 2 个模型配置')
    expect(wrapper.text()).toContain('没有可用于知识问答、AI 规划或 Agent')
    expect(wrapper.text()).not.toContain('AI 已就绪')
    wrapper.unmount()
  })

  it('describes saved-and-resolvable config as unverified rather than healthy', async () => {
    const wrapper = await render(payload({ configured: true, providerCount: 1, defaultModel: 'gpt-4o-mini', anyPurposeUsable: true }))
    expect(wrapper.text()).toContain('AI 配置已保存')
    expect(wrapper.text()).toContain('默认模型 gpt-4o-mini')
    expect(wrapper.text()).toContain('可用性需在 AI 设置中测试确认，此处不表示已就绪')
    expect(wrapper.text()).not.toContain('AI 已就绪')
    wrapper.unmount()
  })

  it('keeps an unknown state with an explicit retry when loading fails', async () => {
    vi.mocked(homeApi.get).mockRejectedValue(new Error('network down'))
    const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/:pathMatch(.*)*', component: HomeView }] })
    await router.push('/home')
    const wrapper = mount(HomeView, { global: { plugins: [router], directives: { loading: () => {} }, stubs } })
    await flushPromises()
    expect(wrapper.text()).toContain('工作台暂时无法加载')
    expect(wrapper.text()).toContain('AI 状态当前未知')
    expect(wrapper.text()).not.toContain('还没有保存任何 AI 模型配置')
    expect(wrapper.text()).not.toContain('未配置')
    wrapper.unmount()
  })

  it('ignores a late response from a superseded request', async () => {
    let resolveFirst: (value: unknown) => void = () => {}
    const first = new Promise((resolve) => { resolveFirst = resolve })
    vi.mocked(homeApi.get)
      .mockReturnValueOnce(first as never)
      .mockResolvedValueOnce({ data: payload({ configured: true, providerCount: 1, anyPurposeUsable: true }) } as never)
    const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/:pathMatch(.*)*', component: HomeView }] })
    await router.push('/home')
    const wrapper = mount(HomeView, { global: { plugins: [router], directives: { loading: () => {} }, stubs } })
    await wrapper.findAll('button').find((b) => b.text() === '刷新')!.trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('AI 配置已保存')
    resolveFirst({ data: payload() })
    await flushPromises()
    // 迟到的“未配置”响应不得覆盖更新后的状态
    expect(wrapper.text()).toContain('AI 配置已保存')
    expect(wrapper.text()).not.toContain('还没有保存任何 AI 模型配置')
    wrapper.unmount()
  })

  it('drops the previous user state when the account changes', async () => {
    let resolveSecond: (value: unknown) => void = () => {}
    const wrapper = await render(payload({ configured: true, providerCount: 1, anyPurposeUsable: true }))
    expect(wrapper.text()).toContain('AI 配置已保存')

    vi.mocked(homeApi.get).mockReturnValueOnce(new Promise((resolve) => { resolveSecond = resolve }) as never)
    authState.currentUser = { id: 'user-2', displayName: '另一用户' }
    authState.listeners.forEach((cb) => cb())
    await flushPromises()
    // 新账号数据返回前，不得继续显示上一个账号的 AI 状态
    expect(wrapper.text()).not.toContain('AI 配置已保存')

    resolveSecond({ data: payload({ configured: true, providerCount: 3, anyPurposeUsable: true }) })
    await flushPromises()
    expect(wrapper.text()).toContain('已保存 3 个模型配置')
    wrapper.unmount()
  })
})
