// @vitest-environment jsdom
import { createPinia, setActivePinia } from 'pinia'
import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { adminNotificationApi, type AdminNotification } from '../../api/admin-notification-api'
import { useAdminNotificationStore } from '../../stores/admin-notification-store'
import AdminNotificationsView from './AdminNotificationsView.vue'

const push = vi.hoisted(() => vi.fn())
const success = vi.hoisted(() => vi.fn())

vi.mock('vue-router', () => ({
  useRouter: () => ({ push }),
}))
vi.mock('element-plus', () => ({
  ElMessage: { success },
}))
vi.mock('../../api/admin-notification-api', () => ({
  adminNotificationApi: {
    list: vi.fn(),
    unreadCount: vi.fn(),
    markAsRead: vi.fn(),
    markAllAsRead: vi.fn(),
  },
}))

const notification = (overrides: Partial<AdminNotification> = {}): AdminNotification => ({
  id: 'n-1',
  category: 'SYSTEM',
  level: 'INFO',
  title: '系统通知',
  content: '内容',
  isRead: false,
  createdAt: '2026-11-15T00:00:00Z',
  ...overrides,
})

const stubs = {
  ElButton: {
    inheritAttrs: false,
    props: ['disabled'],
    emits: ['click'],
    template: '<button :disabled="disabled" v-bind="$attrs" @click="$emit(\'click\')"><slot /></button>',
  },
  ElTag: { template: '<span><slot /></span>' },
  ElIcon: { template: '<span><slot /></span>' },
  ElRadioGroup: { template: '<div><slot /></div>' },
  ElRadioButton: { props: ['value'], template: '<span><slot /></span>' },
}

async function renderView(items: AdminNotification[], unreadCount = 2) {
  vi.mocked(adminNotificationApi.list).mockResolvedValue({ data: items } as never)
  vi.mocked(adminNotificationApi.unreadCount).mockResolvedValue({ data: { unreadCount } } as never)
  vi.mocked(adminNotificationApi.markAsRead).mockResolvedValue({ data: undefined } as never)
  vi.mocked(adminNotificationApi.markAllAsRead).mockResolvedValue({ data: undefined } as never)

  const pinia = createPinia()
  setActivePinia(pinia)
  const wrapper = mount(AdminNotificationsView, {
    global: {
      plugins: [pinia],
      directives: { loading: () => undefined },
      stubs,
    },
  })
  await flushPromises()
  return { wrapper, store: useAdminNotificationStore() }
}

function buttonByText(wrapper: ReturnType<typeof mount>, text: string) {
  return wrapper.findAll('button').find((button) => button.text() === text)
}

describe('admin notification unread synchronization', () => {
  beforeEach(() => {
    vi.resetAllMocks()
  })

  it('loads the sidebar count and decrements it after one mark-read', async () => {
    const items = [notification(), notification({ id: 'n-2' })]
    const { wrapper, store } = await renderView(items)

    expect(store.unreadCount).toBe(2)
    expect(wrapper.text()).toContain('未读事件 (2)')

    await buttonByText(wrapper, '标为已读')!.trigger('click')
    await flushPromises()

    expect(adminNotificationApi.markAsRead).toHaveBeenCalledWith('n-1')
    expect(store.unreadCount).toBe(1)
    expect(wrapper.text()).toContain('未读事件 (1)')
  })

  it('sets the shared count to zero after mark-all-read', async () => {
    const { wrapper, store } = await renderView([notification()], 1)

    await buttonByText(wrapper, '全部标为已读')!.trigger('click')
    await flushPromises()

    expect(adminNotificationApi.markAllAsRead).toHaveBeenCalledOnce()
    expect(store.unreadCount).toBe(0)
    expect(success).toHaveBeenCalledWith('已全部标记为已读')
    expect(wrapper.text()).toContain('未读事件 (0)')
  })
})
