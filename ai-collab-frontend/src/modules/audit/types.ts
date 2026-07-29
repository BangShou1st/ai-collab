export interface AuditLogItem {
  id: string
  userId: string | null
  userDisplayName: string | null
  action: string
  entityType: string
  entityId: string | null
  detail: Record<string, unknown>
  summary: string
  requestId: string
  createdAt: string
}

export interface AuditLogPage {
  items: AuditLogItem[]
  page: number
  size: number
  total: number
}
