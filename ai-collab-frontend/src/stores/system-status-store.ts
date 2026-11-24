import { defineStore, getActivePinia } from 'pinia'
import { systemStatusApi } from '../api/system-status-api'

export const DEFAULT_MAINTENANCE_MESSAGE = '系统正在维护中，请稍后再试'

export const useSystemStatusStore = defineStore('systemStatus', {
  state: () => ({
    maintenanceMode: false,
    maintenanceMessage: '',
    publicRegistrationEnabled: true,
    loaded: false,
    loading: false,
  }),

  actions: {
    async fetchStatus(): Promise<void> {
      this.loading = true
      try {
        const res = await systemStatusApi.getStatus()
        this.maintenanceMode = Boolean(res.data.maintenanceMode)
        this.maintenanceMessage = res.data.maintenanceMessage || ''
        this.publicRegistrationEnabled = Boolean(res.data.publicRegistrationEnabled)
        this.loaded = true
      } catch {
        // 状态查询失败时不覆盖现有判定，由后续接口响应或手动刷新收敛
      } finally {
        this.loading = false
      }
    },

    enterMaintenance(message?: string | null): void {
      this.maintenanceMode = true
      const trimmed = typeof message === 'string' ? message.trim() : ''
      if (trimmed) {
        this.maintenanceMessage = trimmed
      } else if (!this.maintenanceMessage) {
        this.maintenanceMessage = DEFAULT_MAINTENANCE_MESSAGE
      }
      this.loaded = true
    },

    clearMaintenance(): void {
      this.maintenanceMode = false
      this.maintenanceMessage = ''
      this.loaded = true
    },
  },
})

const FALLBACK_STATUS_STORE = {
  maintenanceMode: false,
  maintenanceMessage: '',
  publicRegistrationEnabled: true,
  loaded: false,
  loading: false,
  async fetchStatus(): Promise<void> {},
  enterMaintenance(): void {},
  clearMaintenance(): void {},
}

export function useSafeSystemStatusStore() {
  return getActivePinia() ? useSystemStatusStore() : FALLBACK_STATUS_STORE
}

interface MaintenancePayloadShape {
  code?: unknown
  message?: unknown
  data?: { maintenanceMessage?: unknown } | null
}

/**
 * 统一解析 HTTP 503 SYSTEM_MAINTENANCE 响应并同步 Pinia 维护状态。
 */
export function syncMaintenanceFromResponsePayload(status: number | undefined, payload: unknown): boolean {
  if (status !== 503 || !getActivePinia() || !payload || typeof payload !== 'object') {
    return false
  }
  const body = payload as MaintenancePayloadShape
  if (body.code !== 'SYSTEM_MAINTENANCE') {
    return false
  }
  const nestedMessage = body.data && typeof body.data === 'object' ? body.data.maintenanceMessage : undefined
  const message = typeof nestedMessage === 'string' && nestedMessage.trim()
    ? nestedMessage
    : (typeof body.message === 'string' ? body.message : '')
  useSystemStatusStore().enterMaintenance(message)
  return true
}
