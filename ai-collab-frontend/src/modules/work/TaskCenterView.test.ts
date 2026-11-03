// @vitest-environment jsdom
import { flushPromises, mount } from '@vue/test-utils'
import { computed, reactive, ref } from 'vue'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import TaskCenterView from './TaskCenterView.vue'
import type { Task } from './types'

const routeState = reactive({
  params: { projectId: 'project-1' },
  query: {} as Record<string, string>,
  path: '/projects/project-1/tasks',
  meta: {} as Record<string, unknown>,
})

const routerMock = vi.hoisted(() => ({
  push: vi.fn(async ({ path, query }: { path?: string; query?: Record<string, string> }) => {
    if (path) routeState.path = path
    if (query) routeState.query = { ...query }
  }),
  replace: vi.fn(),
}))

vi.mock('vue-router', () => ({
  useRoute: () => routeState,
  useRouter: () => routerMock,
}))

const sampleTasks: Task[] = [
  {
    id: 't-1',
    projectId: 'project-1',
    title: '设计系统架构',
    description: '完成基础架构设计',
    milestoneId: 'm-1',
    milestoneName: '里程碑 1',
    assigneeId: 'u-1',
    assigneeDisplayName: '架构师张',
    status: 'IN_PROGRESS',
    priority: 'HIGH',
    estimateHours: 16,
    startDate: '2026-11-01',
    dueDate: '2026-11-10',
    version: 1,
    unfinishedDependencyCount: 0,
    dependencyIds: [],
    sourcePlanId: null,
  },
  {
    id: 't-2',
    projectId: 'project-1',
    title: '编写接口文档',
    description: 'OpenAPI 格式',
    milestoneId: 'm-1',
    milestoneName: '里程碑 1',
    assigneeId: 'u-2',
    assigneeDisplayName: '后端李',
    status: 'TODO',
    priority: 'MEDIUM',
    estimateHours: 8,
    startDate: '2026-11-05',
    dueDate: '2026-11-12',
    version: 1,
    unfinishedDependencyCount: 1,
    dependencyIds: ['t-1'],
    sourcePlanId: null,
  },
]

const mocks = vi.hoisted(() => ({
  canManage: true,
  openTask: vi.fn(),
  deleteTask: vi.fn(),
  updateStatus: vi.fn(),
}))

vi.mock('./use-task-board-view', () => ({
  useTaskBoardView: () => ({
    auth: { currentUser: { id: 'u-1' } },
    statuses: ['TODO', 'IN_PROGRESS', 'BLOCKED', 'DONE', 'CANCELED'],
    priorities: ['LOW', 'MEDIUM', 'HIGH', 'URGENT'],
    project: { name: '竞赛协作项目', role: mocks.canManage ? 'OWNER' : 'MEMBER' },
    members: [{ userId: 'u-1', displayName: '架构师张' }, { userId: 'u-2', displayName: '后端李' }],
    milestones: [{ id: 'm-1', name: '里程碑 1' }],
    tasks: ref(sampleTasks),
    selected: null,
    comments: [],
    drawerVisible: false,
    createVisible: false,
    editVisible: false,
    commentContent: '',
    loading: false,
    openingTaskId: '',
    updatingTaskId: '',
    creatingTask: false,
    savingTask: false,
    deletingTask: false,
    savingDependencies: false,
    addingComment: false,
    commentOperationId: '',
    editTargetStatus: null,
    filters: reactive({ assigneeId: '', priority: '', milestoneId: '' }),
    draggedTask: null,
    dragOverColumn: null,
    selectedTaskIds: new Set<string>(),
    batchOperationVisible: false,
    batchStatus: null,
    batchPriority: null,
    batchAssigneeId: '',
    batchProcessing: false,
    form: {},
    editForm: {},
    createStartDateDisabled: () => false,
    createDueDateDisabled: () => false,
    editStartDateDisabled: () => false,
    editDueDateDisabled: () => false,
    canManage: mocks.canManage,
    visibleTasks: computed(() => sampleTasks.filter(t => t.status !== 'CANCELED')),
    createValidationMessage: '',
    editValidationMessage: '',
    commentValidationMessage: '',
    highlightedSourcePlanId: '',
    columnTasks: (status: string) => sampleTasks.filter(t => t.status === status),
    showMyTasks: vi.fn(),
    onDragStart: vi.fn(),
    onDragEnd: vi.fn(),
    onDragOver: vi.fn(),
    onDragLeave: vi.fn(),
    onDrop: vi.fn(),
    toggleTaskSelection: vi.fn(),
    selectAllTasks: vi.fn(),
    clearSelection: vi.fn(),
    openBatchDialog: vi.fn(),
    executeBatchOperation: vi.fn(),
    canChangeStatus: () => true,
    updateStatus: mocks.updateStatus,
    createTask: vi.fn(),
    openTaskEditor: vi.fn(),
    saveTask: vi.fn(),
    deleteTask: mocks.deleteTask,
    openTask: mocks.openTask,
    saveDependencies: vi.fn(),
    addComment: vi.fn(),
    editComment: vi.fn(),
    deleteComment: vi.fn(),
  }),
}))

const stubs = {
  ElButton: { template: '<button v-bind="$attrs"><slot /></button>' },
  ElDialog: { template: '<div v-if="$attrs.modelValue"><slot /></div>' },
  ElForm: { template: '<form><slot /></form>' },
  ElFormItem: { template: '<div><slot /></div>' },
  ElInput: { template: '<input />' },
  ElInputNumber: { template: '<input type="number" />' },
  ElDatePicker: { template: '<input />' },
  ElTag: { template: '<span class="el-tag-stub"><slot /></span>' },
  ElSelect: { template: '<select><slot /></select>' },
  ElOption: { template: '<option><slot /></option>' },
  ElAlert: { template: '<div><slot /></div>' },
  TaskBoardCard: {
    props: ['task'],
    template: '<div class="task-board-card-stub">{{ task.title }}</div>',
  },
  TaskDetailDrawer: { template: '<div class="drawer-stub" />' },
  GanttView: { template: '<div class="gantt-view-stub">甘特图组件</div>' },
  CalendarView: { template: '<div class="calendar-view-stub">日历组件</div>' },
  DependencyGraphView: { template: '<div class="dependency-graph-stub">依赖图组件</div>' },
}

function mountComponent() {
  return mount(TaskCenterView, {
    global: {
      directives: { loading: () => undefined },
      stubs,
    },
  })
}

describe('TaskCenterView', () => {
  beforeEach(() => {
    routeState.params = { projectId: 'project-1' }
    routeState.query = {}
    routeState.path = '/projects/project-1/tasks'
    routeState.meta = {}
    routerMock.push.mockClear()
    mocks.canManage = true
    mocks.openTask.mockClear()
  })

  it('renders unified task center header with all 5 view tabs', () => {
    const wrapper = mountComponent()
    expect(wrapper.get('h1').text()).toBe('任务中心')

    const tabs = wrapper.findAll('.task-view-tab')
    expect(tabs).toHaveLength(5)
    expect(tabs.map(t => t.text())).toEqual(['看板', '列表', '甘特图', '日历', '任务依赖'])
  })

  it('defaults to board view and renders Kanban columns', () => {
    const wrapper = mountComponent()
    expect(wrapper.find('[data-test="board-view-container"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="list-view-container"]').exists()).toBe(false)
    expect(wrapper.find('.task-view-tab.active').text()).toBe('看板')
  })

  it('switches to list view when list tab is clicked and updates route', async () => {
    const wrapper = mountComponent()
    const listTab = wrapper.get('[data-test="tab-list"]')
    await listTab.trigger('click')

    expect(routerMock.push).toHaveBeenCalledWith({
      path: '/projects/project-1/tasks',
      query: { view: 'list' },
    })

    // Simulate route query update
    routeState.query = { view: 'list' }
    await flushPromises()

    expect(wrapper.find('[data-test="list-view-container"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="board-view-container"]').exists()).toBe(false)
  })

  it('switches to gantt, calendar, and dependency views on demand', async () => {
    const wrapper = mountComponent()

    await wrapper.get('[data-test="tab-gantt"]').trigger('click')
    expect(routerMock.push).toHaveBeenCalledWith({
      path: '/projects/project-1/tasks',
      query: { view: 'gantt' },
    })

    routeState.query = { view: 'gantt' }
    await flushPromises()
    expect(wrapper.find('.gantt-view-stub').exists()).toBe(true)

    await wrapper.get('[data-test="tab-calendar"]').trigger('click')
    routeState.query = { view: 'calendar' }
    await flushPromises()
    expect(wrapper.find('.calendar-view-stub').exists()).toBe(true)

    await wrapper.get('[data-test="tab-dependency-graph"]').trigger('click')
    routeState.query = { view: 'dependency-graph' }
    await flushPromises()
    expect(wrapper.find('.dependency-graph-stub').exists()).toBe(true)
  })

  it('renders compact task table in list view with task details', async () => {
    routeState.query = { view: 'list' }
    const wrapper = mountComponent()
    await flushPromises()

    const listView = wrapper.find('[data-test="task-list-view"]')
    expect(listView.exists()).toBe(true)
    expect(listView.text()).toContain('设计系统架构')
    expect(listView.text()).toContain('编写接口文档')
    expect(listView.text()).toContain('架构师张')
    expect(listView.text()).toContain('后端李')
  })

  it('opens task drawer when task row is clicked in list view', async () => {
    routeState.query = { view: 'list' }
    const wrapper = mountComponent()
    await flushPromises()

    const firstRow = wrapper.findAll('.task-row')[0]!
    await firstRow.trigger('click')

    expect(mocks.openTask).toHaveBeenCalledWith(sampleTasks[0])
  })

  it('respects initialView for legacy routes like /board and /gantt', () => {
    routeState.path = '/projects/project-1/gantt'
    routeState.meta = { initialView: 'gantt' }
    const wrapper = mountComponent()

    expect(wrapper.find('.task-view-tab.active').text()).toBe('甘特图')
    expect(wrapper.find('.gantt-view-stub').exists()).toBe(true)
  })
})
