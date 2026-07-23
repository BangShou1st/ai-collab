import AxiosMockAdapter from 'axios-mock-adapter'
import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { authApi } from '../src/api/auth-api'
import { httpClient } from '../src/api/http-client'
import { setUnauthorizedHandler } from '../src/auth/unauthorized-handler'
import { useAuthStore } from '../src/stores/auth-store'

vi.mock('../src/api/auth-api', () => ({
  authApi: {
    login: vi.fn(),
    refresh: vi.fn(),
    me: vi.fn(),
    logout: vi.fn(),
  },
}))

describe('401 refresh coordinator', () => {
  const unauthorized = vi.fn()

  beforeEach(() => {
    setActivePinia(createPinia())
    vi.mocked(authApi.refresh).mockReset()
    unauthorized.mockReset()
    setUnauthorizedHandler(unauthorized)
  })

  it('shares one refresh for concurrent 401 responses and retries each request once', async () => {
    const mock = new AxiosMockAdapter(httpClient)
    let attempts = 0
    mock.onGet('/protected').reply(() => {
      attempts += 1
      return attempts <= 2 ? [401] : [200, { ok: true }]
    })
    vi.mocked(authApi.refresh).mockResolvedValue({
      httpStatus: 200,
      code: 'SUCCESS',
      message: '请求成功',
      data: {
        accessToken: 'fresh-token',
        tokenType: 'Bearer',
        expiresInSeconds: 1800,
      },
    })

    const [first, second] = await Promise.all([
      httpClient.get('/protected'),
      httpClient.get('/protected'),
    ])

    expect(first.data.ok).toBe(true)
    expect(second.data.ok).toBe(true)
    expect(authApi.refresh).toHaveBeenCalledTimes(1)
    expect(attempts).toBe(4)
    mock.restore()
  })

  it('does not recursively refresh and clears auth when refresh fails', async () => {
    const mock = new AxiosMockAdapter(httpClient)
    mock.onGet('/protected').reply(401)
    vi.mocked(authApi.refresh).mockRejectedValue({ response: { status: 401 } })
    const store = useAuthStore()
    store.accessToken = 'expired'

    await expect(httpClient.get('/protected')).rejects.toBeTruthy()

    expect(authApi.refresh).toHaveBeenCalledTimes(1)
    expect(store.accessToken).toBeNull()
    mock.restore()
  })

  it('shares one refresh between a manual refresh and an overlapping 401', async () => {
    let resolveRefresh!: (value: {
      httpStatus: number
      code: string
      message: string
      data: { accessToken: string; tokenType: string; expiresInSeconds: number }
    }) => void
    const refreshPromise = new Promise<{
      httpStatus: number
      code: string
      message: string
      data: { accessToken: string; tokenType: string; expiresInSeconds: number }
    }>((resolve) => {
      resolveRefresh = resolve
    })
    vi.mocked(authApi.refresh).mockReturnValue(refreshPromise)
    const store = useAuthStore()
    store.accessToken = 'old-test-value'
    const storeRefresh = vi.spyOn(store, 'refresh')

    const manualRefresh = store.refresh()
    const mock = new AxiosMockAdapter(httpClient)
    let attempts = 0
    mock.onGet('/overlap').reply(() => {
      attempts += 1
      return attempts === 1 ? [401] : [200, { ok: true }]
    })
    const protectedRequest = httpClient.get('/overlap')

    await vi.waitFor(() => {
      expect(storeRefresh).toHaveBeenCalledTimes(2)
      expect(authApi.refresh).toHaveBeenCalledTimes(1)
    })
    resolveRefresh({
      httpStatus: 200,
      code: 'SUCCESS',
      message: '请求成功',
      data: {
        accessToken: 'new-test-value',
        tokenType: 'Bearer',
        expiresInSeconds: 1800,
      },
    })

    const [, response] = await Promise.all([manualRefresh, protectedRequest])

    expect(response.data.ok).toBe(true)
    expect(authApi.refresh).toHaveBeenCalledTimes(1)
    expect(attempts).toBe(2)
    expect(unauthorized).not.toHaveBeenCalled()
    expect(store.refreshing).toBe(false)
    mock.restore()
  })
})
