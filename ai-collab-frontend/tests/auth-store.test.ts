import { AxiosError, AxiosHeaders } from 'axios'
import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { authApi } from '../src/api/auth-api'
import { useAuthStore } from '../src/stores/auth-store'

vi.mock('../src/api/auth-api', () => ({
  authApi: {
    login: vi.fn(),
    refresh: vi.fn(),
    me: vi.fn(),
    logout: vi.fn(),
  },
}))

const user = {
  id: '1',
  username: 'owner',
  displayName: 'Owner',
  email: null,
  status: 'ACTIVE' as const,
}

function deferred<T>() {
  let resolve!: (value: T | PromiseLike<T>) => void
  let reject!: (reason?: unknown) => void
  const promise = new Promise<T>((resolvePromise, rejectPromise) => {
    resolve = resolvePromise
    reject = rejectPromise
  })
  return { promise, resolve, reject }
}

const refreshed = {
  httpStatus: 200,
  code: 'SUCCESS',
  message: '请求成功',
  data: {
    accessToken: 'test-access-value',
    tokenType: 'Bearer',
    expiresInSeconds: 1800,
  },
}

function axiosError(status: number, message: string): AxiosError {
  return new AxiosError(
    'request failed',
    undefined,
    undefined,
    undefined,
    {
      status,
      statusText: '',
      headers: {},
      config: { headers: new AxiosHeaders() },
      data: { code: 'AUTH_ERROR', message, data: null },
    },
  )
}

describe('auth store', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.mocked(authApi.login).mockReset()
    vi.mocked(authApi.refresh).mockReset()
    vi.mocked(authApi.me).mockReset()
    vi.mocked(authApi.logout).mockReset()
  })

  it('keeps login token and user only in memory', async () => {
    const localSet = vi.spyOn(Storage.prototype, 'setItem')
    vi.mocked(authApi.login).mockResolvedValue({
      httpStatus: 200,
      code: 'SUCCESS',
      message: '请求成功',
      data: {
        accessToken: 'access-token',
        tokenType: 'Bearer',
        expiresInSeconds: 1800,
        user,
      },
    })
    const store = useAuthStore()

    await store.login('owner', 'secret-password')

    expect(store.accessToken).toBe('access-token')
    expect(store.currentUser).toEqual(user)
    expect(localSet).not.toHaveBeenCalled()
    expect(JSON.stringify(store.$state)).not.toContain('secret-password')
  })

  it('clears memory state even when logout request fails', async () => {
    vi.mocked(authApi.logout).mockRejectedValue(new Error('network'))
    const store = useAuthStore()
    store.accessToken = 'access-token'
    store.currentUser = user

    await expect(store.logout()).rejects.toThrow('network')

    expect(store.accessToken).toBeNull()
    expect(store.currentUser).toBeNull()
  })

  it('stays unauthenticated when initialize refresh receives 401', async () => {
    vi.mocked(authApi.refresh).mockRejectedValue(axiosError(401, '未登录'))
    const store = useAuthStore()

    await store.initialize()

    expect(store.initialized).toBe(true)
    expect(store.accessToken).toBeNull()
    expect(store.currentUser).toBeNull()
    expect(store.initializationError).toBeNull()
    expect(authApi.me).not.toHaveBeenCalled()
  })

  it('keeps a safe initialization error when refresh receives 500', async () => {
    vi.mocked(authApi.refresh).mockRejectedValue(axiosError(500, '服务暂时不可用'))
    const store = useAuthStore()

    await store.initialize()

    expect(store.initialized).toBe(true)
    expect(store.accessToken).toBeNull()
    expect(store.currentUser).toBeNull()
    expect(store.initializationError).toEqual({
      httpStatus: 500,
      code: 'AUTH_ERROR',
      message: '服务暂时不可用',
      data: null,
    })
    expect(authApi.refresh).toHaveBeenCalledTimes(1)
    expect(authApi.me).not.toHaveBeenCalled()
  })

  it('keeps a safe initialization error for a network failure', async () => {
    vi.mocked(authApi.refresh).mockRejectedValue(new AxiosError('Network Error', 'ERR_NETWORK'))
    const store = useAuthStore()

    await store.initialize()

    expect(store.initialized).toBe(true)
    expect(store.initializationError?.code).toBe('NETWORK_ERROR')
    expect(store.initializationError?.message).toBe('网络连接失败，请检查网络后重试')
    expect(authApi.refresh).toHaveBeenCalledTimes(1)
  })

  it('shares one refresh request between concurrent store refresh calls', async () => {
    const pending = deferred<typeof refreshed>()
    vi.mocked(authApi.refresh).mockReturnValue(pending.promise)
    const store = useAuthStore()

    const first = store.refresh()
    const second = store.refresh()

    expect(store.refreshing).toBe(true)
    expect(authApi.refresh).toHaveBeenCalledTimes(1)
    pending.resolve(refreshed)

    const [firstResult, secondResult] = await Promise.all([first, second])

    expect(firstResult).toEqual(refreshed)
    expect(secondResult).toEqual(refreshed)
    expect(store.accessToken).toBe('test-access-value')
    expect(store.refreshing).toBe(false)
  })

  it('shares refresh failure, clears auth and allows a later refresh', async () => {
    const pending = deferred<typeof refreshed>()
    vi.mocked(authApi.refresh).mockReturnValueOnce(pending.promise)
    const store = useAuthStore()
    store.accessToken = 'expired-test-value'
    store.currentUser = user

    const first = store.refresh()
    const second = store.refresh()
    const failure = axiosError(500, '服务暂时不可用')
    pending.reject(failure)

    const outcomes = await Promise.allSettled([first, second])

    expect(outcomes.map((outcome) => outcome.status)).toEqual(['rejected', 'rejected'])
    expect(authApi.refresh).toHaveBeenCalledTimes(1)
    expect(store.accessToken).toBeNull()
    expect(store.currentUser).toBeNull()
    expect(store.refreshing).toBe(false)

    vi.mocked(authApi.refresh).mockResolvedValueOnce(refreshed)
    await expect(store.refresh()).resolves.toEqual(refreshed)
    expect(authApi.refresh).toHaveBeenCalledTimes(2)
  })
})
