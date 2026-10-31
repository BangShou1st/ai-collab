// @vitest-environment jsdom
import { flushPromises, mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import ResetPasswordView from './ResetPasswordView.vue'

const mocks = vi.hoisted(() => ({
  resetPassword: vi.fn(),
}))

vi.mock('../../api/auth-api', () => ({
  authApi: mocks,
}))

vi.mock('element-plus', () => ({
  ElMessage: { success: vi.fn(), error: vi.fn() },
}))

vi.mock('vue-router', () => ({
  useRoute: () => ({ query: { token: 'a'.repeat(43) } }),
}))

function mountView() {
  return mount(ResetPasswordView, {
    global: {
      stubs: {
        RouterLink: { props: ['to'], template: '<a><slot /></a>' },
        ElAlert: {
          props: ['title', 'description', 'type'],
          template: '<div class="alert">{{ title }}</div>',
        },
        ElButton: {
          props: ['disabled'],
          emits: ['click'],
          template: '<button :disabled="disabled" @click="$emit(\'click\')"><slot /></button>',
        },
        ElCard: { template: '<article><slot name="header" /><slot /></article>' },
        ElForm: { template: '<form @submit="$emit(\'submit\', $event)"><slot /></form>' },
        ElFormItem: { template: '<label><slot /></label>' },
        ElInput: { template: '<input />' },
      },
    },
  })
}

describe('ResetPasswordView', () => {
  it('提交成功后展示成功状态与登录入口', async () => {
    mocks.resetPassword.mockResolvedValue({ data: null })
    const wrapper = mountView()
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(mocks.resetPassword).toHaveBeenCalledWith('a'.repeat(43), '')
    expect(wrapper.text()).toContain('密码已重置')
    expect(wrapper.text()).toContain('前往登录')
  })

  it('令牌失效时切换到重新申请入口', async () => {
    mocks.resetPassword.mockRejectedValue({
      isAxiosError: true,
      response: { status: 410, data: { code: 'ACCOUNT_TOKEN_INVALID', message: '链接无效或已过期' } },
    })
    const wrapper = mountView()
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(wrapper.text()).toContain('重置链接无效或已过期')
    expect(wrapper.text()).toContain('重新申请密码重置')
  })
})
