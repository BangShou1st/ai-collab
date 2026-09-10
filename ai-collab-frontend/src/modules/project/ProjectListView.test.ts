// @vitest-environment jsdom
import { flushPromises, mount } from '@vue/test-utils'
import { defineComponent, h } from 'vue'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import ProjectListView from './ProjectListView.vue'

const mocks = vi.hoisted(() => ({
  list: vi.fn(),
  create: vi.fn(),
  update: vi.fn(),
  remove: vi.fn(),
}))

vi.mock('./project-api', () => ({
  projectApi: mocks,
}))

vi.mock('element-plus', () => ({
  ElMessage: { success: vi.fn() },
  ElMessageBox: { confirm: vi.fn().mockResolvedValue(true) },
}))

const ButtonStub = defineComponent({
  props: ['disabled'],
  emits: ['click'],
  setup(props, { emit, slots }) {
    return () => h('button', {
      disabled: props.disabled,
      onClick: () => emit('click'),
    }, slots.default?.())
  },
})

function project(role: 'OWNER' | 'ADMIN' | 'MEMBER') {
  return {
    id: `${role.toLowerCase()}-project`,
    name: `${role} 项目`,
    description: '',
    startDate: null,
    dueDate: null,
    status: 'ACTIVE',
    version: 2,
    role,
  }
}

async function mounted() {
  const wrapper = mount(ProjectListView, {
    global: {
      directives: {
        loading: () => {},
      },
      stubs: {
        PageHeader: { template: '<header><slot name="actions" /></header>' },
        RouterLink: { props: ['to'], template: '<a><slot /></a>' },
        ElAlert: { props: ['title'], template: '<div>{{ title }}</div>' },
        ElButton: ButtonStub,
        ElCard: { template: '<article><slot name="header" /><slot /></article>' },
        ElDialog: { template: '<div><slot /></div>' },
        ElForm: { template: '<form><slot /></form>' },
        ElFormItem: { template: '<label><slot /></label>' },
        ElInput: { template: '<input />' },
        ElDatePicker: { template: '<input />' },
        ElSelect: { template: '<select><slot /></select>' },
        ElOption: { template: '<option />' },
        ElTag: { template: '<span><slot /></span>' },
        ElEmpty: { template: '<div />' },
      },
    },
  })
  await flushPromises()
  return wrapper
}

beforeEach(() => {
  vi.clearAllMocks()
  mocks.list.mockResolvedValue({ data: [project('OWNER'), project('ADMIN'), project('MEMBER')] })
  mocks.update.mockResolvedValue({ data: project('OWNER') })
  mocks.remove.mockResolvedValue(undefined)
})

describe('ProjectListView project management', () => {
  it('only exposes the overflow menu for projects owned by the current user', async () => {
    const wrapper = await mounted()

    expect(wrapper.findAll('[aria-label="更多操作"]')).toHaveLength(1)
    expect(wrapper.findAll('[data-action="delete-project"]')).toHaveLength(0)
  })
})
