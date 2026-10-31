// @vitest-environment jsdom
import { flushPromises, mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import VerifyEmailView from './VerifyEmailView.vue'

const mocks = vi.hoisted(() => ({
  verifyEmail: vi.fn(),
}))

vi.mock('../../api/auth-api', () => ({
  authApi: mocks,
}))

vi.mock('element-plus', () => ({
  ElMessage: { success: vi.fn(), error: vi.fn() },
}))

vi.mock('vue-router', () => ({
  useRoute: () => ({ query: { token: 'b'.repeat(43) } }),
}))

function mountView() {
  return mount(VerifyEmailView, {
    global: {
      stubs: {
        RouterLink: { props: ['to'], template: '<a><slot /></a>' },
        ElAlert: { props: ['title'], template: '<div class="alert">{{ title }}</div>' },
        ElButton: { template: '<button><slot /></button>' },
        ElCard: { template: '<article><slot name="header" /><slot /></article>' },
        ElSkeleton: { template: '<div />' },
      },
    },
  })
}

describe('VerifyEmailView', () => {
  it('自动验证成功后展示成功状态', async () => {
    mocks.verifyEmail.mockResolvedValue({ data: null })
    const wrapper = mountView()
    await flushPromises()

    expect(mocks.verifyEmail).toHaveBeenCalledWith('b'.repeat(43))
    expect(wrapper.text()).toContain('邮箱验证成功')
  })

  it('验证失败时提示重新发送', async () => {
    mocks.verifyEmail.mockRejectedValue({
      isAxiosError: true,
      response: { status: 410, data: { code: 'ACCOUNT_TOKEN_INVALID', message: '链接无效或已过期' } },
    })
    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.text()).toContain('验证链接无效或已过期')
    expect(wrapper.text()).toContain('前往登录')
  })
})
