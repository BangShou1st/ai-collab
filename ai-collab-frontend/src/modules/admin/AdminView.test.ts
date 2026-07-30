// @vitest-environment jsdom
import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import AdminView from './AdminView.vue'

const mocks = vi.hoisted(() => ({
  models: vi.fn(),
  assignments: vi.fn(),
  users: vi.fn(),
}))

vi.mock('element-plus', () => ({
  ElMessage: { success: vi.fn(), error: vi.fn() },
  ElMessageBox: { confirm: vi.fn() },
}))

vi.mock('./admin-api', () => ({
  adminApi: {
    models: mocks.models,
    assignments: mocks.assignments,
    users: mocks.users,
  },
}))

beforeEach(() => {
  vi.clearAllMocks()
  mocks.models.mockResolvedValue({ data: [] })
  mocks.assignments.mockResolvedValue({ data: [] })
  mocks.users.mockResolvedValue({ data: [] })
})

describe('AdminView', () => {
  it('loads and presents model assignment, model configuration, and account management', async () => {
    const wrapper = mount(AdminView, {
      global: {
        directives: { loading: () => undefined },
        stubs: {
          PageHeader: { template: '<header><slot name="actions" /></header>' },
          ElAlert: { props: ['title'], template: '<div>{{ title }}</div>' },
          ElCard: { template: '<section><slot name="header" /><slot /></section>' },
          ElTable: { template: '<div><slot /></div>' },
          ElTableColumn: { props: ['label'], template: '<div>{{ label }}</div>' },
          ElButton: { template: '<button><slot /></button>' },
          ElSelect: { template: '<div><slot /></div>' },
          ElOption: { template: '<span />' },
          ElTag: { template: '<span><slot /></span>' },
          ElDialog: { template: '<div />' },
          ElForm: { template: '<form><slot /></form>' },
          ElFormItem: { template: '<label><slot /></label>' },
          ElInput: { template: '<input />' },
          ElInputNumber: { template: '<input />' },
          ElCheckboxGroup: { template: '<div><slot /></div>' },
          ElCheckbox: { template: '<span><slot /></span>' },
          ElSwitch: { template: '<input type="checkbox" />' },
        },
      },
    })
    await flushPromises()

    expect(mocks.models).toHaveBeenCalledOnce()
    expect(mocks.assignments).toHaveBeenCalledOnce()
    expect(mocks.users).toHaveBeenCalledOnce()
    expect(wrapper.text()).toContain('用途分配')
    expect(wrapper.text()).toContain('模型配置')
    expect(wrapper.text()).toContain('账号管理')
    expect(wrapper.text()).toContain('新建账号')
    expect(wrapper.text()).toContain('添加模型')
  })
})
