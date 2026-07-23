import { defineStore } from 'pinia'
import { authApi } from '../api/auth-api'
import { normalizeApiError } from '../api/api-result'
import { coordinateRefresh } from '../auth/auth-refresh-coordinator'
import type { AccessTokenResult, ApiResult, CurrentUser, LoginResult } from '../api/types'

export const useAuthStore = defineStore('auth', {
  state: () => ({
    accessToken: null as string | null,
    currentUser: null as CurrentUser | null,
    initialized: false,
    authenticating: false,
    refreshing: false,
    initializationError: null as ApiResult<unknown> | null,
  }),

  getters: {
    isAuthenticated: (state) => Boolean(state.accessToken && state.currentUser),
  },

  actions: {
    clearAuth(): void {
      this.accessToken = null
      this.currentUser = null
    },

    clearInitializationError(): void {
      this.initializationError = null
    },

    async login(username: string, password: string): Promise<ApiResult<LoginResult>> {
      this.clearInitializationError()
      this.authenticating = true
      try {
        const result = await authApi.login(username, password)
        this.accessToken = result.data.accessToken
        this.currentUser = result.data.user
        this.initialized = true
        this.initializationError = null
        return result
      } finally {
        this.authenticating = false
      }
    },

    async refresh(): Promise<ApiResult<AccessTokenResult>> {
      this.refreshing = true
      try {
        const result = await coordinateRefresh(() => authApi.refresh())
        this.accessToken = result.data.accessToken
        return result
      } catch (error) {
        this.clearAuth()
        throw error
      } finally {
        this.refreshing = false
      }
    },

    async loadCurrentUser(): Promise<ApiResult<CurrentUser>> {
      if (!this.accessToken) {
        this.currentUser = null
        throw new Error('No access token is available')
      }
      try {
        const result = await authApi.me(this.accessToken)
        this.currentUser = result.data
        return result
      } catch (error) {
        this.clearAuth()
        throw error
      }
    },

    async logout(): Promise<ApiResult<null>> {
      try {
        return await authApi.logout()
      } finally {
        this.clearAuth()
        this.initialized = true
      }
    },

    async initialize(): Promise<void> {
      if (this.initialized) return
      this.initializationError = null
      try {
        await this.refresh()
        await this.loadCurrentUser()
      } catch (error) {
        this.clearAuth()
        const safeError = normalizeApiError(error)
        if (safeError.httpStatus !== 401) {
          this.initializationError = safeError
        }
      } finally {
        this.initialized = true
      }
    },
  },
})
