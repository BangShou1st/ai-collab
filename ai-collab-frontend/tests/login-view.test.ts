import { AxiosError, AxiosHeaders } from 'axios'
import { createPinia } from 'pinia'
import { flushPromises, mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import { authApi } from '../src/api/auth-api'
import type { ApiResult } from '../src/api/types'
import { useAuthStore } from '../src/stores/auth-store'
import LoginView from '../src/views/LoginView.vue'

vi.mock('vue-router', () => ({
  useRouter: () => ({ replace: vi.fn() }),
  useRoute: () => ({ query: {} }),
}))

vi.mock('../src/api/auth-api', () => ({
  authApi: {
    login: vi.fn(),
    refresh: vi.fn(),
    me: vi.fn(),
    logout: vi.fn(),
  },
}))

describe('LoginView', () => {
  function mountView(initializationError: ApiResult<unknown> | null = null) {
    const pinia = createPinia()
    const store = useAuthStore(pinia)
    store.initializationError = initializationError
    const wrapper = mount(LoginView, {
      global: {
        plugins: [pinia],
        stubs: {
          'el-card': { template: '<section><slot name="header" /><slot /></section>' },
          'el-form': { template: '<form @submit="$emit(\'submit\', $event)"><slot /></form>' },
          'el-form-item': { template: '<div><slot /></div>' },
          'el-input': {
            props: ['modelValue'],
            template: '<input :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />',
          },
          'el-alert': { props: ['title'], template: '<div>{{ title }}</div>' },
          'el-button': { template: '<button type="submit"><slot /></button>' },
        },
      },
    })
    return { wrapper, store }
  }

  it('prefers a safe backend message when login fails', async () => {
    vi.mocked(authApi.login).mockRejectedValue(
      new AxiosError(
        'request failed',
        undefined,
        undefined,
        undefined,
        {
          status: 403,
          statusText: '',
          headers: {},
          config: { headers: new AxiosHeaders() },
          data: { code: 'ACCOUNT_DISABLED', message: '账号已禁用', data: null },
        },
      ),
    )
    const { wrapper } = mountView()
    const inputs = wrapper.findAll('input')
    await inputs[0].setValue('owner')
    await inputs[1].setValue('secret')
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(wrapper.text()).toContain('账号已禁用')
    expect(wrapper.text()).not.toContain('请检查用户名和密码')
    expect(wrapper.text()).not.toContain('secret')
  })

  it('shows a safe initialization HTTP error', () => {
    const { wrapper } = mountView({
      httpStatus: 500,
      code: 'INTERNAL_ERROR',
      message: '服务暂时不可用',
      data: {
        safe: '可展示',
        accessToken: 'hidden-test-value',
        Authorization: 'hidden',
        Cookie: 'hidden',
        password: 'hidden',
      },
    })

    const panel = wrapper.find('.result-panel').text()
    expect(panel).toContain('500')
    expect(panel).toContain('INTERNAL_ERROR')
    expect(panel).toContain('服务暂时不可用')
    expect(panel).toContain('"safe": "可展示"')
    expect(panel).not.toContain('hidden-test-value')
    expect(panel).not.toMatch(/authorization|cookie|password|token/i)
  })

  it('shows a safe initialization network error', () => {
    const { wrapper } = mountView({
      httpStatus: null,
      code: 'NETWORK_ERROR',
      message: '网络连接失败，请检查网络后重试',
      data: null,
    })

    expect(wrapper.text()).toContain('NETWORK_ERROR')
    expect(wrapper.text()).toContain('网络连接失败，请检查网络后重试')
  })

  it('clears an old initialization error before a new login attempt', async () => {
    vi.mocked(authApi.login).mockRejectedValue(
      new AxiosError(
        'request failed',
        undefined,
        undefined,
        undefined,
        {
          status: 401,
          statusText: '',
          headers: {},
          config: { headers: new AxiosHeaders() },
          data: { code: 'AUTH_FAILED', message: '本次登录失败', data: null },
        },
      ),
    )
    const { wrapper, store } = mountView({
      httpStatus: 500,
      code: 'OLD_ERROR',
      message: '旧初始化错误',
      data: null,
    })
    const inputs = wrapper.findAll('input')
    await inputs[0].setValue('owner')
    await inputs[1].setValue('test-password')
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(store.initializationError).toBeNull()
    expect(wrapper.text()).toContain('本次登录失败')
    expect(wrapper.text()).not.toContain('旧初始化错误')
  })
})
