// @vitest-environment jsdom
import { flushPromises, mount } from '@vue/test-utils'
import { defineComponent, h } from 'vue'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import PlanComparisonView from './PlanComparisonView.vue'

const mocks = vi.hoisted(() => ({
  route: { params: { projectId: 'project-1' }, query: {} as Record<string, string> },
  projectGet: vi.fn(),
  planList: vi.fn(),
  planComparison: vi.fn(),
}))

vi.mock('vue-router', () => ({
  useRoute: () => mocks.route,
}))

vi.mock('../project/project-api', () => ({
  projectApi: { get: mocks.projectGet },
}))

vi.mock('../planning/planning-api', () => ({
  planningApi: { list: mocks.planList },
}))

vi.mock('./report-api', () => ({
  reportApi: { planComparison: mocks.planComparison },
}))

const SelectStub = defineComponent({
  name: 'ElSelect',
  props: ['modelValue'],
  emits: ['update:modelValue', 'change'],
  setup(_, { emit, slots }) {
    return () => h('button', {
      class: 'plan-select',
      onClick: () => {
        emit('update:modelValue', 'plan-1')
        emit('change', 'plan-1')
      },
    }, slots.default?.())
  },
})

beforeEach(() => {
  vi.clearAllMocks()
  mocks.route.query = {}
  mocks.projectGet.mockResolvedValue({ data: { id: 'project-1', name: '竞赛项目' } })
  mocks.planList.mockResolvedValue({
    data: {
      data: [{
        id: 'plan-1',
        title: '浏览器验收规划',
        latestVersionNo: 2,
        status: 'READY',
      }],
    },
  })
  mocks.planComparison.mockResolvedValue({
    data: {
      planId: 'plan-1',
      planName: '浏览器验收规划',
      taskComparisons: [],
      summary: {
        totalPlanned: 0,
        matchedTasks: 0,
        modifiedTasks: 0,
        missingTasks: 0,
        extraTasks: 0,
      },
    },
  })
})

describe('PlanComparisonView', () => {
  it('lets the user choose a plan without supplying an internal plan id', async () => {
    const wrapper = mount(PlanComparisonView, {
      global: {
        directives: { loading: () => undefined },
        stubs: {
          PageHeader: { template: '<header />' },
          ElAlert: { props: ['title'], template: '<div>{{ title }}</div>' },
          ElSelect: SelectStub,
          ElOption: { props: ['label'], template: '<span>{{ label }}</span>' },
          ElEmpty: { props: ['description'], template: '<div>{{ description }}</div>' },
          ElCard: { template: '<section><slot name="header" /><slot /></section>' },
          ElTable: { template: '<div><slot /></div>' },
          ElTableColumn: { template: '<div><slot /></div>' },
          ElTag: { template: '<span><slot /></span>' },
        },
      },
    })
    await flushPromises()

    expect(wrapper.text()).toContain('请选择要对比的任务规划')
    expect(wrapper.text()).not.toContain('请提供规划 ID')
    expect(mocks.planComparison).not.toHaveBeenCalled()

    await wrapper.get('.plan-select').trigger('click')
    await flushPromises()

    expect(mocks.planComparison).toHaveBeenCalledWith('project-1', 'plan-1')
  })
})
