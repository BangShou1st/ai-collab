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

describe('onboarding guide and workspace metrics', () => {
  beforeEach(() => {
    localStorage.clear()
  })

  it('renders onboarding guide with 0/3 progress for brand new user', async () => {
    const wrapper = await render(payload({}, 0))
    expect(wrapper.text()).toContain('开始你的协作之旅')
    expect(wrapper.text()).toContain('已完成 0/3 核心项')
    expect(wrapper.text()).toContain('创建首个项目')
    expect(wrapper.text()).toContain('立即创建')
    expect(wrapper.text()).toContain('启用 AI 辅助')
    expect(wrapper.text()).toContain('立即配置')
    expect(wrapper.text()).toContain('全部清空')
    expect(wrapper.text()).toContain('还没有项目')
    wrapper.unmount()
  })

  it('automatically hides onboarding guide for experienced users with projects, tasks and AI', async () => {
    const experiencedData = {
      recentProjects: [{ id: 'proj-1', name: '竞赛项目', status: 'ACTIVE', role: 'OWNER' }],
      myTasks: [{ id: 'task-1', projectId: 'proj-1', projectName: '竞赛项目', title: '核心开发', status: 'IN_PROGRESS', priority: 'HIGH', dueDate: '2026-12-01' }],
      notificationsSummary: { unread: 2 },
      pendingApprovals: 1,
      aiConfigSummary: summary({ configured: true, providerCount: 1, anyPurposeUsable: true }),
      recentActivity: [],
    }
    const wrapper = await render(experiencedData)
    // 老用户默认不展开引导卡片
    expect(wrapper.find('.onboarding-card').exists()).toBe(false)
    // 顶部操作栏提供展开引导按钮
    const guideToggle = wrapper.findAll('button').find((b) => b.text().includes('新手引导'))
    expect(guideToggle).toBeDefined()

    // 点击后可以重新查看引导
    await guideToggle!.trigger('click')
    await flushPromises()
    expect(wrapper.find('.onboarding-card').exists()).toBe(true)
    expect(wrapper.text()).toContain('已完成 3/3 核心项')
    wrapper.unmount()
  })

  it('allows user to dismiss guide and persist state in localStorage', async () => {
    const wrapper = await render(payload({}, 0))
    expect(wrapper.find('.onboarding-card').exists()).toBe(true)

    // 点击收起引导
    const dismissBtn = wrapper.find('button.dismiss-btn')
    expect(dismissBtn.exists()).toBe(true)
    await dismissBtn.trigger('click')
    await flushPromises()

    // 引导卡片收起
    expect(wrapper.find('.onboarding-card').exists()).toBe(false)
    // 恢复按钮出现
    const restoreBtn = wrapper.findAll('button').find((b) => b.text().includes('新手引导'))
    expect(restoreBtn).toBeDefined()

    // 重新点击恢复展开
    await restoreBtn!.trigger('click')
    await flushPromises()
    expect(wrapper.find('.onboarding-card').exists()).toBe(true)
    wrapper.unmount()
  })

  it('renders metric badges and identifies overdue tasks', async () => {
    const overdueData = {
      recentProjects: [{ id: 'p1', name: '逾期测试项目', status: 'ACTIVE', role: 'MEMBER' }],
      myTasks: [
        { id: 't1', projectId: 'p1', projectName: '逾期测试项目', title: '延期工作', status: 'TODO', priority: 'URGENT', dueDate: '2026-01-01' },
      ],
      notificationsSummary: { unread: 3 },
      pendingApprovals: 2,
      aiConfigSummary: summary(),
      recentActivity: [],
    }
    const wrapper = await render(overdueData)
    expect(wrapper.text()).toContain('我的待办')
    expect(wrapper.text()).toContain('1 项逾期')
    expect(wrapper.text()).toContain('需确认')
    expect(wrapper.text()).toContain('新消息')
    expect(wrapper.text()).toContain('活跃空间')
    expect(wrapper.text()).toContain('已逾期')
    expect(wrapper.text()).toContain('紧急')
    wrapper.unmount()
  })
})
