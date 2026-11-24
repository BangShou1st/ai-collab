// @vitest-environment jsdom
import { computed } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import AdminShell from './AdminShell.vue'

vi.mock('vue-router', () => ({
  useRoute: () => ({ path: '/admin/settings', fullPath: '/admin/settings' }),
  useRouter: () => ({ replace: vi.fn(), push: vi.fn() }),
}))
vi.mock('element-plus', () => ({ ElMessageBox: { confirm: vi.fn() } }))
vi.mock('../stores/auth-store', () => ({
  useAuthStore: () => ({ currentUser: { displayName: '管理员', username: 'admin', systemAdmin: true }, logout: vi.fn() }),
}))
vi.mock('../stores/admin-notification-store', () => ({
  useAdminNotificationStore: () => ({ unreadCount: 0, fetchUnreadCount: vi.fn() }),
}))

const stubs = {
  RouterLink: {
    props: ['to'],
    setup(props: Record<string, unknown>) {
      const dataTo = computed(() => (typeof props.to === 'string' ? props.to : ''))
      return { dataTo }
    },
    template: '<a :data-to="dataTo"><slot /></a>',
  },
  ElIcon: { template: '<span><slot /></span>' },
  MaintenanceAdminBanner: { template: '<div data-test="banner-stub" />' },
}

describe('AdminShell navigation', () => {
  it('exposes workspace and account switch entries for system admins', async () => {
    const wrapper = mount(AdminShell, { slots: { default: '<div />' }, global: { stubs } })
    await flushPromises()
    expect(wrapper.find('[data-test="admin-to-workspace"]').attributes('data-to')).toBe('/home')
    expect(wrapper.find('[data-test="admin-to-account"]').attributes('data-to')).toBe('/account')
  })
})
