import type { RouteLocationRaw } from 'vue-router'
import type { Notification } from './types'

const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i
const validId = (value: unknown): value is string => typeof value === 'string' && uuid.test(value)

// Only this entity has an approved destination. Content is never treated as a URL.
// AppShell and task APIs still enforce membership and project/task association.
export function notificationTaskRoute(notification: Notification): RouteLocationRaw | null {
  if (notification.entityType !== 'TASK' || !validId(notification.projectId) || !validId(notification.entityId)) return null
  return { path: `/projects/${notification.projectId}/board`, query: { taskId: notification.entityId } }
}
