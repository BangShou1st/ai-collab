// @vitest-environment jsdom
import { mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import TaskBoardView from './TaskBoardView.vue'

const mocks = vi.hoisted(() => ({ canManage: true }))

vi.mock('./use-task-board-view', () => ({
  useTaskBoardView: () => ({
    auth: { currentUser: null },
    statuses: [],
    priorities: [],
    project: { name: 'P2 E2E 合成项目', role: mocks.canManage ? 'OWNER' : 'MEMBER' },
    members: [],
    milestones: [],
    tasks: [],
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
    filters: { assigneeId: '', priority: '', milestoneId: '' },
    draggedTask: null,
    dragOverColumn: null,
    selectedTaskIds: new Set(),
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
    createValidationMessage: '',
    editValidationMessage: '',
    commentValidationMessage: '',
    highlightedSourcePlanId: '',
    columnTasks: () => [],
    showMyTasks: () => {},
    onDragStart: () => {},
    onDragEnd: () => {},
    onDragOver: () => {},
    onDragLeave: () => {},
    onDrop: () => {},
    toggleTaskSelection: () => {},
    selectAllTasks: () => {},
    clearSelection: () => {},
    openBatchDialog: () => {},
    executeBatchOperation: () => {},
    canChangeStatus: () => false,
    updateStatus: () => {},
    createTask: () => {},
    openTaskEditor: () => {},
    saveTask: () => {},
    deleteTask: () => {},
    openTask: () => {},
    saveDependencies: () => {},
    addComment: () => {},
    editComment: () => {},
    deleteComment: () => {},
  }),
}))

function mountView() {
  return mount(TaskBoardView, {
    global: {
      directives: { loading: () => {} },
      stubs: {
        ElButton: { template: '<button><slot /></button>' },
        ElDialog: { template: '<div />' },
        ElForm: true,
        ElFormItem: true,
        ElInput: true,
        ElInputNumber: true,
        ElDatePicker: true,
        ElTag: true,
        ElSelect: true,
        ElOption: true,
        ElAlert: true,
        TaskBoardCard: true,
        TaskDetailDrawer: true,
      },
    },
  })
}

describe('TaskBoardView page header actions', () => {
  beforeEach(() => {
    mocks.canManage = true
  })

  it('renders the page title and management actions for an owner', () => {
    const wrapper = mountView()

    expect(wrapper.get('h1').text()).toBe('任务看板')
    expect(wrapper.text()).toContain('新建任务')
    expect(wrapper.text()).toContain('批量操作')
  })

  it('keeps management actions hidden for a member while showing the page title', () => {
    mocks.canManage = false
    const wrapper = mountView()

    expect(wrapper.get('h1').text()).toBe('任务看板')
    expect(wrapper.text()).not.toContain('新建任务')
    expect(wrapper.text()).not.toContain('批量操作')
    expect(wrapper.text()).toContain('我的任务')
  })
})
