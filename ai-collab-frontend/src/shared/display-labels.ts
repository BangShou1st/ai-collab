const roleLabels: Record<string, string> = {
  OWNER: '所有者',
  ADMIN: '管理员',
  MEMBER: '成员',
}

const userStatusLabels: Record<string, string> = {
  ACTIVE: '正常',
  DISABLED: '已禁用',
}

const projectStatusLabels: Record<string, string> = {
  ACTIVE: '进行中',
  ARCHIVED: '已归档',
}

const milestoneStatusLabels: Record<string, string> = {
  PLANNED: '计划中',
  ACTIVE: '进行中',
  COMPLETED: '已完成',
  CANCELED: '已取消',
}

const taskStatusLabels: Record<string, string> = {
  TODO: '待处理',
  IN_PROGRESS: '进行中',
  BLOCKED: '已阻塞',
  DONE: '已完成',
  CANCELED: '已取消',
}

const taskPriorityLabels: Record<string, string> = {
  LOW: '低',
  MEDIUM: '中',
  HIGH: '高',
  URGENT: '紧急',
}

const documentStatusLabels: Record<string, string> = {
  UPLOADED: '已上传',
  PARSING: '解析中',
  INDEXING: '索引中',
  READY: '可检索',
  FAILED: '处理失败',
  DELETING: '删除中',
}

export function roleLabel(value: string | null | undefined): string {
  return value ? roleLabels[value] ?? '未知角色' : '未知角色'
}

export function userStatusLabel(value: string | null | undefined): string {
  return value ? userStatusLabels[value] ?? '未知状态' : '未知状态'
}

export function projectStatusLabel(value: string | null | undefined): string {
  return value ? projectStatusLabels[value] ?? '未知状态' : '未知状态'
}

export function milestoneStatusLabel(value: string | null | undefined): string {
  return value ? milestoneStatusLabels[value] ?? '未知状态' : '未知状态'
}

export function taskStatusLabel(value: string | null | undefined): string {
  return value ? taskStatusLabels[value] ?? '未知状态' : '未知状态'
}

export function taskPriorityLabel(value: string | null | undefined): string {
  return value ? taskPriorityLabels[value] ?? '未知优先级' : '未知优先级'
}

export function documentStatusLabel(value: string | null | undefined): string {
  return value ? documentStatusLabels[value] ?? '未知状态' : '未知状态'
}

export function documentStatusType(
  value: string,
): 'info' | 'warning' | 'success' | 'danger' {
  if (value === 'READY') return 'success'
  if (value === 'FAILED') return 'danger'
  if (value === 'PARSING' || value === 'INDEXING' || value === 'DELETING') return 'warning'
  return 'info'
}

export function formatDate(value: string | null | undefined): string {
  if (!value) return '未设置'
  const dateOnly = /^(\d{4})-(\d{2})-(\d{2})/.exec(value)
  if (dateOnly) return `${dateOnly[1]}年${dateOnly[2]}月${dateOnly[3]}日`
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return '日期无效'
  const parts = dateParts(date, {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  })
  return `${parts.year}年${parts.month}月${parts.day}日`
}

export function formatDateTime(value: string | null | undefined): string {
  if (!value) return '未设置'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return '时间无效'
  const parts = dateParts(date, {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  })
  return `${parts.year}年${parts.month}月${parts.day}日 ${parts.hour}:${parts.minute}`
}

function dateParts(
  date: Date,
  options: Intl.DateTimeFormatOptions,
): Record<string, string> {
  return Object.fromEntries(
    new Intl.DateTimeFormat('zh-CN', options)
      .formatToParts(date)
      .filter(part => part.type !== 'literal')
      .map(part => [part.type, part.value]),
  )
}
