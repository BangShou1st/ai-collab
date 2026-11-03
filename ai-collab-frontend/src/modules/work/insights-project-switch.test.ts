// @vitest-environment jsdom
import { flushPromises, mount } from '@vue/test-utils'
import { defineComponent, h, reactive, type VNode } from 'vue'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import MemberLoadView from './MemberLoadView.vue'
import WeeklyReportView from './WeeklyReportView.vue'
import RiskAnalysisView from './RiskAnalysisView.vue'
import PlanComparisonView from './PlanComparisonView.vue'

// 项目洞察四视图共有的契约：项目切换必须重载、旧请求不得覆盖新项目数据。
// 用 reactive route 模拟真实路由参数变化，驱动视图内的 watch(projectId)。
function createRoute() {
  return reactive({ params: { projectId: 'p1' }, query: {} as Record<string, string> })
}

const mocks = vi.hoisted(() => ({
  routerPush: vi.fn(),
  projectGet: vi.fn(),
  memberLoad: vi.fn(),
  weeklyReport: vi.fn(),
  riskAnalysis: vi.fn(),
  planList: vi.fn(),
  planComparison: vi.fn(),
}))

vi.mock('vue-router', () => ({
  useRoute: () => testRoute,
  useRouter: () => ({ push: mocks.routerPush }),
}))

vi.mock('../project/project-api', () => ({
  projectApi: { get: mocks.projectGet },
}))

vi.mock('./visualization-api', () => ({
  visualizationApi: { memberLoad: mocks.memberLoad },
}))

vi.mock('./report-api', () => ({
  reportApi: {
    weeklyReport: mocks.weeklyReport,
    riskAnalysis: mocks.riskAnalysis,
    planComparison: mocks.planComparison,
  },
}))

vi.mock('../planning/planning-api', () => ({
  planningApi: { list: mocks.planList },
}))

import type { RouteLocationNormalizedLoaded } from 'vue-router'
let testRoute: RouteLocationNormalizedLoaded

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((r) => { resolve = r })
  return { promise, resolve }
}

const globalStubs = {
  directives: { loading: () => undefined },
  stubs: {
    PageHeader: { template: '<header><slot name="actions" /></header>' },
    ElButton: {
      props: ['loading', 'type'],
      emits: ['click'],
      template: '<button :data-loading="String(loading)" @click="$emit(\'click\')"><slot /></button>',
    },
    ElResult: {
      props: ['title'],
      template: '<div class="el-result">{{ title }}<slot name="extra" /></div>',
    },
    ElEmpty: { props: ['description'], template: '<div class="el-empty">{{ description }}</div>' },
    ElCard: { template: '<section><slot name="header" /><slot /></section>' },
    ElTag: { template: '<span><slot /></span>' },
    ElProgress: { props: ['percentage'], template: '<div class="el-progress" />' },
    // 逐行重放列的 scoped slot，使 #default="{ row }" 在测试中拿到真实行数据
    ElTable: {
      props: ['data'],
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      setup(props: { data?: Array<Record<string, unknown>> }, { slots }: { slots: any }) {
        return () => {
          const columns = (slots.default ? slots.default({}) : []) as VNode[]
          const rows = (props.data ?? []) as Array<Record<string, unknown>>
          return h(
            'div',
            rows.map(row => h('div', { class: 'table-row' },
              // eslint-disable-next-line @typescript-eslint/no-explicit-any
              columns.map(col => h(col.type as any, { ...(col.props ?? {}), row }, col.children as any)))),
          )
        }
      },
    },
    ElTableColumn: {
      props: ['label', 'row'],
      template: '<div class="table-cell"><slot :row="row" /></div>',
    },
    EmptyState: { props: ['title'], template: '<div class="empty-state">{{ title }}</div>' },
  },
}

function memberPayload(total: number) {
  return {
    data: {
      members: [{
        userId: `u-${total}`,
        displayName: `成员${total}`,
        totalTasks: total,
        completedTasks: 0,
        inProgressTasks: 0,
        overdueTasks: 0,
        totalEstimateHours: 0,
      }],
    },
  }
}

beforeEach(() => {
  vi.clearAllMocks()
  testRoute = createRoute() as unknown as RouteLocationNormalizedLoaded
  mocks.projectGet.mockImplementation((pid: string) =>
    Promise.resolve({ data: { id: pid, name: `项目${pid}` } }))
})

describe('项目洞察四视图：项目切换与请求竞态契约', () => {
  it('MemberLoadView 切换项目后以新项目重载，旧项目的慢响应不覆盖新数据', async () => {
    const slow = deferred<ReturnType<typeof memberPayload>>()
    mocks.memberLoad.mockImplementation((pid: string) =>
      pid === 'p1' ? slow.promise : Promise.resolve(memberPayload(2)))

    const wrapper = mount(MemberLoadView, { global: globalStubs })
    await flushPromises()
    expect(mocks.memberLoad).toHaveBeenLastCalledWith('p1')

    // 切换到 p2：watch 触发重载
    testRoute.params.projectId = 'p2'
    await flushPromises()
    expect(mocks.memberLoad).toHaveBeenLastCalledWith('p2')
    expect(wrapper.text()).toContain('成员2')

    // p1 的慢响应此刻才返回：必须被丢弃
    slow.resolve(memberPayload(99))
    await flushPromises()
    expect(wrapper.text()).toContain('成员2')
    expect(wrapper.text()).not.toContain('成员99')
  })

  it('WeeklyReportView 快速连续切换时只保留最后一次请求结果', async () => {
    const first = deferred<{ data: unknown }>()
    const fullReport = (total: number) => ({
      data: {
        reportDate: `2026-11-0${total}`,
        taskStats: { totalTasks: total, completedTasks: 0, inProgressTasks: 0, overdueTasks: 0, completionRate: 0 },
        milestoneStats: { totalMilestones: 0, completedMilestones: 0, upcomingMilestones: 0, overdueMilestones: 0 },
        topContributors: [],
        highlights: [],
        risks: [],
      },
    })
    mocks.weeklyReport.mockImplementation((pid: string) =>
      pid === 'p1' ? first.promise : Promise.resolve(fullReport(7)))

    const wrapper = mount(WeeklyReportView, { global: globalStubs })
    await flushPromises()

    testRoute.params.projectId = 'p2'
    await flushPromises()
    expect(wrapper.text()).toContain('7')

    first.resolve(fullReport(1))
    await flushPromises()
    expect(wrapper.text()).toContain('7')
    expect(wrapper.text()).not.toContain('2026-11-01')
  })

  it('RiskAnalysisView 汇总展示涉及任务数口径，有 taskId 的风险可跳转任务', async () => {
    mocks.riskAnalysis.mockResolvedValue({
      data: {
        risks: [
          { taskId: 't-1', taskTitle: '逾期任务', riskType: 'OVERDUE', severity: 'HIGH', description: '已逾期 3 天', dueDate: '2026-11-01', assigneeName: null },
          { taskId: 't-1', taskTitle: '逾期任务', riskType: 'BLOCKED', severity: 'HIGH', description: '被前置任务阻塞', dueDate: '2026-11-01', assigneeName: null },
          { taskId: null, taskTitle: '汇总型风险', riskType: 'UNASSIGNED', severity: 'LOW', description: '未分配', dueDate: null, assigneeName: null },
        ],
        summary: { highRiskCount: 2, mediumRiskCount: 0, lowRiskCount: 1, overallAssessment: '' },
      },
    })

    const wrapper = mount(RiskAnalysisView, { global: globalStubs })
    await flushPromises()

    // 3 条风险事件但只涉及 2 个任务：口径必须可见
    expect(wrapper.text()).toContain('涉及任务数')
    expect(wrapper.text()).toContain('2')

    const links = wrapper.findAll('button.task-link')
    expect(links.length).toBe(2)
    await links[0].trigger('click')
    expect(mocks.routerPush).toHaveBeenCalledWith(
      expect.stringContaining('/projects/p1/tasks?'),
    )
  })

  it('PlanComparisonView 切换项目后清空旧规划选择与对比结果', async () => {
    mocks.planList.mockImplementation((pid: string) =>
      Promise.resolve({ data: { data: [{ id: `plan-${pid}`, title: `规划${pid}`, latestVersionNo: 1, status: 'READY' }] } }))
    mocks.planComparison.mockImplementation((_pid: string, planId: string) =>
      Promise.resolve({
        data: { planId, planName: `对比-${planId}`, taskComparisons: [], summary: { totalPlanned: 0, matchedTasks: 0, modifiedTasks: 0, missingTasks: 0, extraTasks: 0 } },
      }))

    testRoute.query.planId = 'plan-p1'
    const wrapper = mount(PlanComparisonView, { global: globalStubs })
    await flushPromises()
    expect(mocks.planComparison).toHaveBeenCalledWith('p1', 'plan-p1')
    expect(wrapper.text()).toContain('对比-plan-p1')

    testRoute.params.projectId = 'p2'
    await flushPromises()
    // 旧对比结果必须被清空：自动选中新项目首个规划并加载新结果
    expect(mocks.planComparison).toHaveBeenLastCalledWith('p2', 'plan-p2')
    expect(wrapper.text()).toContain('对比-plan-p2')
    expect(wrapper.text()).not.toContain('对比-plan-p1')
  })
})
