import { defineStore } from 'pinia'
import { adminNotificationApi } from '../api/admin-notification-api'

export const useAdminNotificationStore = defineStore('admin-notifications', {
  state: () => ({
    unreadCount: 0,
    countLoaded: false,
  }),

  actions: {
    setUnreadCount(count: number): void {
      this.unreadCount = Number.isFinite(count) ? Math.max(0, Math.trunc(count)) : 0
      this.countLoaded = true
    },

    decrementUnread(amount = 1): void {
      this.setUnreadCount(this.unreadCount - Math.max(0, amount))
    },

    async fetchUnreadCount(): Promise<number> {
      const result = await adminNotificationApi.unreadCount()
      this.setUnreadCount(result.data.unreadCount)
      return this.unreadCount
    },
  },
})
