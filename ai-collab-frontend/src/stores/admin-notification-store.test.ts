import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { adminNotificationApi } from '../api/admin-notification-api'
import { useAdminNotificationStore } from './admin-notification-store'

vi.mock('../api/admin-notification-api', () => ({
  adminNotificationApi: {
    unreadCount: vi.fn(),
  },
}))

describe('admin notification store', () => {
  beforeEach(() => {
    vi.resetAllMocks()
    setActivePinia(createPinia())
  })

  it('loads the authoritative unread count', async () => {
    vi.mocked(adminNotificationApi.unreadCount).mockResolvedValue({ data: { unreadCount: 7 } } as never)
    const store = useAdminNotificationStore()

    await expect(store.fetchUnreadCount()).resolves.toBe(7)
    expect(store.unreadCount).toBe(7)
    expect(store.countLoaded).toBe(true)
  })

  it('decrements immediately and never renders a negative badge', () => {
    const store = useAdminNotificationStore()
    store.setUnreadCount(2)

    store.decrementUnread()
    expect(store.unreadCount).toBe(1)
    store.decrementUnread()
    store.decrementUnread()
    expect(store.unreadCount).toBe(0)
  })

  it('normalizes invalid and fractional counts', () => {
    const store = useAdminNotificationStore()

    store.setUnreadCount(3.9)
    expect(store.unreadCount).toBe(3)
    store.setUnreadCount(Number.NaN)
    expect(store.unreadCount).toBe(0)
  })
})
