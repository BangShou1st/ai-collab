// @vitest-environment jsdom
import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { systemStatusApi } from '../api/system-status-api'
import { syncMaintenanceFromResponsePayload, useSystemStatusStore } from './system-status-store'

vi.mock('../api/system-status-api', () => ({
  systemStatusApi: {
    getStatus: vi.fn(),
  },
}))

describe('system-status-store', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })

  it('loads status and toggles maintenance state cleanly', async () => {
    vi.mocked(systemStatusApi.getStatus).mockResolvedValue({
      httpStatus: 200,
      code: 'SUCCESS',
      message: '操作成功',
      data: {
        maintenanceMode: true,
        maintenanceMessage: '预计停站 15 分钟',
        publicRegistrationEnabled: false,
      },
    })

    const store = useSystemStatusStore()
    await store.fetchStatus()
    expect(store.loaded).toBe(true)
    expect(store.maintenanceMode).toBe(true)
    expect(store.maintenanceMessage).toBe('预计停站 15 分钟')
    expect(store.publicRegistrationEnabled).toBe(false)

    store.clearMaintenance()
    expect(store.maintenanceMode).toBe(false)
    expect(store.maintenanceMessage).toBe('')

    store.enterMaintenance('')
    expect(store.maintenanceMode).toBe(true)
    expect(store.maintenanceMessage).toBe('系统正在维护中，请稍后再试')
  })

  it('syncs maintenance state from 503 SYSTEM_MAINTENANCE payload and ignores others', () => {
    const store = useSystemStatusStore()
    expect(syncMaintenanceFromResponsePayload(503, { code: 'OTHER' })).toBe(false)
    expect(store.maintenanceMode).toBe(false)

    expect(syncMaintenanceFromResponsePayload(503, {
      code: 'SYSTEM_MAINTENANCE',
      message: '维护公告',
      data: { maintenanceMessage: '停站升级至 18:00' },
    })).toBe(true)
    expect(store.maintenanceMode).toBe(true)
    expect(store.maintenanceMessage).toBe('停站升级至 18:00')
  })
})
