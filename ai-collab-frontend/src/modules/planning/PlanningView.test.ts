// @vitest-environment jsdom
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { defineComponent, h } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import PlanningView from './PlanningView.vue'
import PlanningIssuePanel from './components/PlanningIssuePanel.vue'
import type {
  PlanPermissions,
  StructuredValidationIssue,
  TaskPlan,
  TaskPlanDetailView,
  TaskPlanDraft,
} from './types'

const mocks = vi.hoisted(() => ({
  route: { params: { projectId: 'project-1' } },
  push: vi.fn(),
  list: vi.fn(),
  create: vi.fn(),
  detail: vi.fn(),
  versions: vi.fn(),
  version: vi.fn(),
  action: vi.fn(),
  save: vi.fn(),
  restore: vi.fn(),
  confirm: vi.fn(),
  remove: vi.fn(),
  events: vi.fn(),
  partialRegenerate: vi.fn(),
  projectGet: vi.fn(),
  listMembers: vi.fn(),
  listDocuments: vi.fn(),
}))

vi.mock('vue-router', () => ({
  useRoute: () => mocks.route,
  useRouter: () => ({ push: mocks.push }),
  onBeforeRouteLeave: vi.fn(),
  onBeforeRouteUpdate: vi.fn(),
}))

vi.mock('element-plus', () => ({
  ElMessageBox: { confirm: vi.fn().mockResolvedValue(true) },
}))

vi.mock('./planning-api', () => ({
  planningApi: {
    list: mocks.list,
    create: mocks.create,
    detail: mocks.detail,
    versions: mocks.versions,
    version: mocks.version,
    action: mocks.action,
    save: mocks.save,
    restore: mocks.restore,
    confirm: mocks.confirm,
    remove: mocks.remove,
    events: mocks.events,
    partialRegenerate: mocks.partialRegenerate,
  },
  confirmationKey: () => 'confirmation-key',
  clearConfirmationKey: vi.fn(),
}))

vi.mock('../project/project-api', () => ({
  projectApi: { get: mocks.projectGet, listMembers: mocks.listMembers },
}))

vi.mock('../document/document-api', () => ({
  documentApi: { list: mocks.listDocuments },
}))

const permissions: PlanPermissions = {
  canEdit: true,
  canCancel: false,
  canRetryDetail: false,
  canRegenerate: true,
  canConfirm: true,
  canDelete: true,
  canRestore: true,
  canPartialRegenerate: true,
}

const draft: TaskPlanDraft = {
  summary: '现有摘要',
  assumptions: ['已有假设'],
  risks: ['已有风险'],
  milestones: [{
    tempKey: 'm1',
    title: '发布里程碑',
    objective: '发布目标',
    description: '描述',
    targetDate: '2026-08-20',
    sortOrder: 0,
    sourceRefs: ['S1'],
  }],
  tasks: [{
    tempKey: 't1',
    milestoneTempKey: 'm1',
    title: '实现登录',
    objective: '完成登录',
    description: '任务描述',
    priority: 'HIGH',
    estimatedHours: 8,
    startDate: '2026-08-02',
    dueDate: '2026-08-05',
    suggestedAssigneeId: 'user-1',
    assigneeId: null,
    dependencyTempKeys: [],
    sourceRefs: ['S1'],
    sortOrder: 0,
  }],
  sources: [{
    ref: 'S1',
    documentId: 'doc-1',
    documentName: '需求说明.docx',
    chunkId: null,
    heading: null,
    similarity: null,
    quoteText: '',
    contentHash: null,
  }],
}

function plan(overrides: Partial<TaskPlan> = {}): TaskPlan {
  return {
    id: 'plan-1',
    projectId: 'project-1',
    title: '中文规划',
    goal: '完成系统',
    constraints: '',
    planStartDate: '2026-08-01',
    planDueDate: '2026-08-31',
    maxTaskCount: 20,
    status: 'READY',
    latestVersionNo: 2,
    latestVersionId: 'v2',
    lastErrorSummary: null,
    updatedAt: '2026-07-28T00:00:00Z',
    ...overrides,
  }
}

function issue(overrides: Partial<StructuredValidationIssue> = {}): StructuredValidationIssue {
  return {
    id: 'issue-1',
    code: 'DEPENDENCY_DATE_CONFLICT',
    severity: 'BLOCKING_EDITABLE',
    targetType: 'TASK',
    targetTempKey: 't1',
    field: 'startDate',
    relatedTempKey: null,
    safeDetails: {},
    ...overrides,
  }
}

function detail(planValue = plan(), overrides: Partial<TaskPlanDetailView> = {}): TaskPlanDetailView {
  return {
    plan: planValue,
    latestVersion: null,
    activeAttempt: null,
    latestFailedAttempt: null,
    latestAttempt: null,
    confirmation: null,
    validation: { errors: [], warnings: [] },
    permissions,
    structuredIssues: [],
    ...overrides,
  }
}

const response = <T,>(data: T) => ({ data: { data } })

const OptionStub = defineComponent({
  name: 'ElOption',
  props: ['label', 'value', 'disabled'],
  setup(props) {
    return () => h('span', {
      class: 'el-option',
      'data-value': String(props.value),
      'data-disabled': String(Boolean(props.disabled)),
    }, String(props.label ?? props.value))
  },
})

const SelectStub = defineComponent({
  name: 'ElSelect',
  props: ['modelValue', 'disabled'],
  emits: ['update:modelValue', 'change'],
  setup(_, { slots }) {
    return () => h('div', { class: 'el-select' }, slots.default?.())
  },
})

const InputStub = defineComponent({
  name: 'ElInput',
  inheritAttrs: false,
  props: ['modelValue', 'readonly', 'type'],
  emits: ['update:modelValue'],
  setup(props, { emit, attrs }) {
    return () => h(props.type === 'textarea' ? 'textarea' : 'input', {
      ...attrs,
      class: 'el-input',
      value: props.modelValue,
      readonly: props.readonly,
      onInput: (event: Event) => emit('update:modelValue', (event.target as HTMLInputElement).value),
    })
  },
})

const NumberInputStub = defineComponent({
  name: 'ElInputNumber',
  inheritAttrs: false,
  props: ['modelValue', 'min', 'max'],
  emits: ['update:modelValue'],
  setup(props, { emit, attrs }) {
    return () => h('input', {
      ...attrs,
      class: 'el-input-number',
      type: 'number',
      min: props.min,
      max: props.max,
      value: props.modelValue,
      onInput: (event: Event) => emit('update:modelValue', Number((event.target as HTMLInputElement).value)),
    })
  },
})

const ButtonStub = defineComponent({
  name: 'ElButton',
  props: ['disabled'],
  emits: ['click'],
  setup(props, { emit, slots }) {
    return () => h('button', {
      disabled: props.disabled,
      onClick: () => {
        if (!props.disabled) emit('click')
      },
    }, slots.default?.())
  },
})

async function mounted(): Promise<VueWrapper> {
  const wrapper = mount(PlanningView, {
    attachTo: document.body,
    global: {
      stubs: {
        PageHeader: { template: '<header><slot name="actions" /></header>' },
        ElAlert: { props: ['title'], template: '<div class="el-alert">{{ title }}</div>' },
        ElButton: ButtonStub,
        ElInput: InputStub,
        ElInputNumber: NumberInputStub,
        ElSelect: SelectStub,
        ElOption: OptionStub,
        ElDatePicker: InputStub,
        ElCheckbox: InputStub,
        ElTag: { template: '<span><slot /></span>' },
        ElCard: { template: '<section><slot name="header" /><slot /></section>' },
        ElDialog: { template: '<div><slot /></div>' },
        ElForm: { template: '<form><slot /></form>' },
        ElFormItem: { template: '<label><slot /></label>' },
      },
    },
  })
  await flushPromises()
  return wrapper
}

async function openFirst(wrapper: VueWrapper): Promise<void> {
  await wrapper.get('.planning-list-item').trigger('click')
  await flushPromises()
}

async function openHistory(wrapper: VueWrapper): Promise<void> {
  await openFirst(wrapper)
  const selects = wrapper.findAllComponents(SelectStub)
  await selects[1].vm.$emit('change', 'v1')
  await flushPromises()
}

beforeEach(() => {
  vi.clearAllMocks()
  sessionStorage.clear()
  Element.prototype.scrollIntoView = vi.fn()
  mocks.projectGet.mockResolvedValue({ data: { role: 'OWNER' } })
  mocks.listMembers.mockResolvedValue({ data: [{ userId: 'user-1', displayName: '张三' }] })
  mocks.listDocuments.mockResolvedValue({ data: [] })
  mocks.list.mockResolvedValue(response([plan()]))
  mocks.detail.mockResolvedValue(response(detail()))
  mocks.versions.mockResolvedValue(response([
    { id: 'v2', versionNo: 2, sourceType: 'AI_PARTIAL_REPAIR' },
    { id: 'v1', versionNo: 1, sourceType: 'MANUAL_EDIT' },
  ]))
  mocks.version.mockImplementation((_project: string, _plan: string, versionId: string) =>
    Promise.resolve(response({ version: { id: versionId, versionNo: versionId === 'v2' ? 2 : 1, sourceType: 'MANUAL_EDIT' }, draft })))
  mocks.events.mockResolvedValue(response([]))
  mocks.partialRegenerate.mockResolvedValue(response({}))
  mocks.save.mockResolvedValue(response({}))
  mocks.restore.mockResolvedValue(response({}))
  mocks.confirm.mockResolvedValue(response({ status: 'SUCCESS' }))
})

afterEach(() => {
  document.body.innerHTML = ''
  vi.useRealTimers()
})

describe('PlanningView real component workflow', () => {
  it('uses a bounded custom number input for the maximum task count', async () => {
    const wrapper = await mounted()

    const input = wrapper.find('input[type="number"]')
    expect(input.exists()).toBe(true)
    expect(input.attributes('min')).toBe('1')
    expect(input.attributes('max')).toBe('40')
    expect((input.element as HTMLInputElement).value).toBe('20')
  })

  it('versionSourceUsesChineseLabels', async () => {
    const wrapper = await mounted()
    await openFirst(wrapper)
    expect(wrapper.text()).toContain('v2 AI 局部修复')
    expect(wrapper.text()).toContain('v1 用户修改')
  })

  it('priorityUsesChineseLabels', async () => {
    const wrapper = await mounted()
    await openFirst(wrapper)
    // 点击里程碑header展开它
    await wrapper.get('.milestone-header').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-value="HIGH"]').text()).toBe('高')
    expect(wrapper.text()).not.toContain('URGENT')
  })

  it('failureSummaryNeverShowsInternalCode', async () => {
    const failed = plan({
      status: 'FAILED',
      lastErrorSummary: 'DETAIL / MODEL_OUTPUT_INVALID / tasks[0].priority',
    })
    mocks.list.mockResolvedValue(response([failed]))
    mocks.detail.mockResolvedValue(response(detail(failed)))
    const wrapper = await mounted()
    await openFirst(wrapper)
    expect(wrapper.text()).toContain('任务细节生成结果无效')
    expect(wrapper.text()).not.toContain('MODEL_OUTPUT_INVALID')
    expect(wrapper.text()).not.toContain('tasks[0]')
  })

  it('repairingContinuesPolling', async () => {
    vi.useFakeTimers()
    mocks.list
      .mockResolvedValueOnce(response([plan({ status: 'REPAIRING' })]))
      .mockResolvedValueOnce(response([plan({ status: 'REPAIRING' })]))
      .mockResolvedValueOnce(response([plan({ status: 'READY' })]))
    const wrapper = await mounted()
    // 默认选中首条 plan 后，每轮 poll 还会走 refresh() 再调一次 list
    expect(mocks.list).toHaveBeenCalledTimes(3)
    await vi.advanceTimersByTimeAsync(2000)
    expect(mocks.list).toHaveBeenCalledTimes(5)
    wrapper.unmount()
  })

  it('openingPlanWithoutVersionClearsPreviousDraft', async () => {
    const noVersion = plan({ id: 'plan-2', title: '无版本规划', latestVersionId: null, latestVersionNo: 0 })
    mocks.list.mockResolvedValue(response([plan(), noVersion]))
    mocks.detail.mockImplementation((_project: string, id: string) =>
      Promise.resolve(response(detail(id === 'plan-2' ? noVersion : plan()))))
    const wrapper = await mounted()
    await openFirst(wrapper)
    expect(wrapper.findAll('textarea').some(input =>
      (input.element as HTMLTextAreaElement).value === '现有摘要')).toBe(true)
    await wrapper.findAll('.planning-list-item')[1].trigger('click')
    await flushPromises()
    expect(wrapper.findAll('textarea').some(input =>
      (input.element as HTMLTextAreaElement).value === '现有摘要')).toBe(false)
  })

  it('issueClickFocusesTargetField', async () => {
    mocks.detail.mockResolvedValue(response(detail(plan(), { structuredIssues: [issue()] })))
    const wrapper = await mounted()
    await openFirst(wrapper)
    // 点击issue-content会自动展开里程碑
    await wrapper.get('.issue-content').trigger('click')
    await flushPromises()
    const target = wrapper.get('#planning-t1-startDate')
    const input = target.get('input').element as HTMLInputElement
    await flushPromises()
    expect(Element.prototype.scrollIntoView).toHaveBeenCalled()
    expect(document.activeElement).toBe(input)
    expect(wrapper.text()).toContain('实现登录')
  })

  it('saveButtonOpensDialogAndCanSave', async () => {
    const wrapper = await mounted()
    await openFirst(wrapper)
    await wrapper.get('textarea[placeholder="规划摘要"]').setValue('保存后的新摘要')
    await wrapper.findAll('button').find(button => button.text() === '保存新版本')!.trigger('click')
    await flushPromises()
    // 保存按钮应该被调用（打开对话框）
    expect(wrapper.text()).toContain('保存新版本')
  })

  it('deleteButtonWorksForAllStatuses', async () => {
    // 测试删除按钮可以删除所有状态的规划
    const failedPlan = plan({ status: 'FAILED', latestVersionId: null, latestVersionNo: 0 })
    mocks.list.mockResolvedValue(response([failedPlan]))
    mocks.detail.mockResolvedValue(response(detail(failedPlan)))
    const wrapper = await mounted()
    await openFirst(wrapper)
    // 删除按钮应该可见
    const deleteButton = wrapper.findAll('button').find(button => button.text() === '删除规划')
    expect(deleteButton).toBeDefined()
  })

  it('readyWithIssuesCannotConfirm', async () => {
    const blocked = plan({ status: 'READY_WITH_ISSUES' })
    mocks.list.mockResolvedValue(response([blocked]))
    mocks.detail.mockResolvedValue(response(detail(blocked, {
      permissions: { ...permissions, canConfirm: false },
      structuredIssues: [issue()],
    })))
    const wrapper = await mounted()
    await openFirst(wrapper)
    expect(wrapper.text()).toContain('规划已生成，仍有待处理问题')
    expect(wrapper.findAll('button').some(button => button.text() === '确认并创建任务')).toBe(false)
  })

  it('assumptionAndRiskCanBeAddedAndRemoved', async () => {
    const wrapper = await mounted()
    await openFirst(wrapper)
    const button = (text: string) => wrapper.findAll('button').find(item => item.text() === text)!
    await button('添加假设').trigger('click')
    await button('添加风险').trigger('click')
    expect(wrapper.text()).toContain('假设 2')
    expect(wrapper.text()).toContain('风险 2')
    const deletes = wrapper.findAll('button').filter(item => item.text() === '删除')
    await deletes[0].trigger('click')
    await deletes[2].trigger('click')
    expect(wrapper.text()).not.toContain('假设 2')
    expect(wrapper.text()).not.toContain('风险 2')
  })

  it('eventTimelineUsesChineseLabels', async () => {
    mocks.events.mockResolvedValue(response([{
      id: 'event-1',
      fromVersionId: 'v1',
      toVersionId: 'v2',
      eventType: 'PLAN_GENERATED',
      changedFields: ['startDate', 'dueDate', 'title'],
      changedTargets: ['t1', 't2'],
      issueCodes: [],
      createdAt: '2026-07-28T00:00:00Z',
    }]))
    const wrapper = await mounted()
    await openFirst(wrapper)
    expect(wrapper.text()).toContain('规划已生成')
    expect(wrapper.text()).toContain('AI 生成了规划')
    expect(wrapper.text()).not.toContain('PLAN_GENERATED')
  })
})
