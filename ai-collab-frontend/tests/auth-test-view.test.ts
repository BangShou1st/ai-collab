import { AxiosError, AxiosHeaders } from 'axios'
import { createPinia } from 'pinia'
import { flushPromises, shallowMount, type VueWrapper } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { authApi } from '../src/api/auth-api'
import type { CurrentUser } from '../src/api/types'
import { useAuthStore } from '../src/stores/auth-store'
import AuthTestView from '../src/views/AuthTestView.vue'

const replace = vi.fn()

vi.mock('vue-router', () => ({
  useRouter: () => ({ replace }),
}))

vi.mock('../src/api/auth-api', () => ({
  authApi: {
    login: vi.fn(),
    refresh: vi.fn(),
    me: vi.fn(),
    logout: vi.fn(),
  },
}))

const user: CurrentUser = {
  id: '1',
  username: 'owner',
  displayName: 'Owner',
  email: null,
  status: 'ACTIVE' as const,
}

const stubs = {
  'el-card': { template: '<section><slot name="header" /><slot /></section>' },
  'el-tag': { template: '<span><slot /></span>' },
  'el-descriptions': { template: '<div><slot /></div>' },
  'el-descriptions-item': {
    props: ['label'],
    template: '<div>{{ label }}<slot /></div>',
  },
  'el-button': {
    template: '<button @click="$emit(\'click\')"><slot /></button>',
  },
}

function mountView(
  accessToken: string | null = 'ZXCVBNMASDFGHJKL',
  currentUser: CurrentUser | null = user,
): VueWrapper {
  const pinia = createPinia()
  const store = useAuthStore(pinia)
  store.accessToken = accessToken
  store.currentUser = currentUser
  store.initialized = true
  return shallowMount(AuthTestView, {
    global: {
      plugins: [pinia],
      stubs,
    },
  })
}

function button(wrapper: VueWrapper, label: string) {
  const candidate = wrapper.findAll('button').find((item) => item.text() === label)
  if (!candidate) throw new Error(`button not found: ${label}`)
  return candidate
}

describe('AuthTestView', () => {
  beforeEach(() => {
    replace.mockReset()
    vi.mocked(authApi.refresh).mockReset()
    vi.mocked(authApi.me).mockReset()
    vi.mocked(authApi.logout).mockReset()
  })

  it('shows only whether the access token exists and never renders its fragments', () => {
    const wrapper = mountView()
    const text = wrapper.text()

    expect(text).toContain('Access Token：已存在')
    expect(text).not.toContain('ZXCVBNMASDFGHJKL')
    expect(text).not.toContain('ZXCV')
    expect(text).not.toContain('ASDF')
    expect(text).not.toContain('HJKL')

    wrapper.unmount()
    const withoutToken = mountView(null)
    expect(withoutToken.text()).toContain('Access Token：不存在')
  })

  it('shows user id, configured email and dynamic authenticated status', () => {
    const wrapper = mountView('ZXCVBNMASDFGHJKL', {
      ...user,
      id: 'user-id-42',
      email: 'owner@example.test',
    })

    expect(wrapper.text()).toContain('user-id-42')
    expect(wrapper.text()).toContain('owner@example.test')
    expect(wrapper.text()).toContain('已登录')
  })

  it('shows an unset email and unauthenticated status from store state', () => {
    const wrapper = mountView(null, user)

    expect(wrapper.text()).toContain('未设置')
    expect(wrapper.text()).toContain('未登录')
  })

  it('shows status, code, message and safe data after loading the current user', async () => {
    vi.mocked(authApi.me).mockResolvedValue({
      httpStatus: 200,
      code: 'SUCCESS',
      message: '请求成功',
      data: user,
    })
    const wrapper = mountView()

    await button(wrapper, '获取当前用户').trigger('click')
    await flushPromises()

    const text = wrapper.text()
    expect(text).toContain('获取当前用户')
    expect(text).toContain('200')
    expect(text).toContain('SUCCESS')
    expect(text).toContain('请求成功')
    expect(text).toContain('"username": "owner"')
  })

  it('renders the backend error message and excludes sensitive response fields', async () => {
    vi.mocked(authApi.refresh).mockRejectedValue(
      new AxiosError(
        'request failed',
        undefined,
        undefined,
        undefined,
        {
          status: 500,
          statusText: '',
          headers: {},
          config: { headers: new AxiosHeaders() },
          data: {
            code: 'INTERNAL_ERROR',
            message: '服务暂时不可用',
            data: {
              accessToken: 'ZXCVBNMASDFGHJKL',
              password: 'never-render',
              Authorization: 'Bearer hidden',
              Cookie: 'refresh=hidden',
              safe: 'visible',
            },
          },
        },
      ),
    )
    const wrapper = mountView()

    await button(wrapper, '主动刷新').trigger('click')
    await flushPromises()

    const panel = wrapper.find('.result-panel').text()
    expect(panel).toContain('服务暂时不可用')
    expect(panel).toContain('"safe": "visible"')
    expect(panel).not.toContain('ZXCV')
    expect(panel).not.toContain('never-render')
    expect(panel).not.toMatch(/authorization|cookie|password|token/i)
  })

  it('records logout, clears local auth, routes to login and can clear the result', async () => {
    vi.mocked(authApi.logout).mockResolvedValue({
      httpStatus: 200,
      code: 'SUCCESS',
      message: '退出成功',
      data: null,
    })
    const wrapper = mountView()

    await button(wrapper, '退出当前设备').trigger('click')
    await flushPromises()

    expect(wrapper.find('.result-panel').text()).toContain('退出成功')
    expect(replace).toHaveBeenCalledWith('/login')
    expect(wrapper.text()).toContain('Access Token：不存在')

    await button(wrapper, '清空请求结果').trigger('click')
    expect(wrapper.find('.result-panel').exists()).toBe(false)
  })
})
