import axios, { AxiosError, type InternalAxiosRequestConfig } from 'axios'
import { handleUnauthorized } from '../auth/unauthorized-handler'
import { useAuthStore } from '../stores/auth-store'

interface RetryRequestConfig extends InternalAxiosRequestConfig {
  _authRetried?: boolean
}

const NON_REFRESHABLE_PATHS = ['/auth/login', '/auth/refresh', '/auth/logout']

export const httpClient = axios.create({
  baseURL: '/api/v1',
  withCredentials: true,
})

/**
 * 仅供无需登录的公开接口使用，不附加 Access Token，也不触发刷新流程。
 * 邀请码和密码只能作为当前请求参数存在，调用方不得记录或持久化。
 */
export const anonymousHttpClient = axios.create({
  baseURL: '/api/v1',
  withCredentials: true,
})

httpClient.interceptors.request.use((config) => {
  const token = useAuthStore().accessToken
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

httpClient.interceptors.response.use(
  (response) => response,
  async (error: AxiosError) => {
    const config = error.config as RetryRequestConfig | undefined
    const path = config?.url ?? ''
    const isExcluded = NON_REFRESHABLE_PATHS.some((candidate) => path.includes(candidate))
    if (error.response?.status !== 401 || !config || config._authRetried || isExcluded) {
      return Promise.reject(error)
    }

    config._authRetried = true
    const auth = useAuthStore()
    try {
      const result = await auth.refresh()
      const token = result.data.accessToken
      config.headers.Authorization = `Bearer ${token}`
      return await httpClient.request(config)
    } catch (refreshError) {
      auth.clearAuth()
      handleUnauthorized()
      return Promise.reject(refreshError)
    }
  },
)
