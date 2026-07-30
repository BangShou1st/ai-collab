import {
  formatDate,
  milestoneStatusLabel,
  taskPriorityLabel,
  taskStatusLabel,
} from '../../shared/display-labels'
import type { AgentApproval } from './types'

export interface ApprovalField {
  label: string
  value: string
}

export interface ApprovalPresentation {
  actionLabel: string
  statusLabel: string
  fields: ApprovalField[]
}

const actionLabels: Record<string, string> = {
  create_task_after_approval: '创建任务',
  update_task_after_approval: '更新任务',
  create_milestone_after_approval: '创建里程碑',
  update_milestone_after_approval: '更新里程碑',
}

const statusLabels: Record<AgentApproval['status'], string> = {
  PENDING: '待审批',
  APPROVED: '已批准',
  REJECTED: '已拒绝',
  EXPIRED: '已过期',
  CONFLICTED: '数据已变化',
}

function record(value: unknown): Record<string, unknown> {
  return value && typeof value === 'object' && !Array.isArray(value)
    ? value as Record<string, unknown>
    : {}
}

function text(value: unknown): string | null {
  if (typeof value === 'string') return value.trim() || null
  if (typeof value === 'number' && Number.isFinite(value)) return String(value)
  return null
}

function field(label: string, value: string | null): ApprovalField | null {
  return value ? { label, value } : null
}

export function presentApproval(approval: AgentApproval): ApprovalPresentation {
  const actionLabel = actionLabels[approval.toolName] ?? '项目变更提案'
  const milestone = actionLabel.includes('里程碑')
  const diff = record(approval.diff)
  const after = record(diff.after)
  const source = Object.keys(after).length ? after : record(approval.arguments)

  const title = text(source.title)
  const status = text(source.status)
  const priority = text(source.priority)
  const estimateHours = text(source.estimateHours)
  const description = text(source.description)
  const objective = text(source.objective)
  const startDate = text(source.startDate)
  const dueDate = text(source.dueDate) ?? text(source.targetDate)
  const assigneeSpecified = text(source.assigneeId) ? '已指定' : null
  const milestoneSpecified = text(source.milestoneId) ? '已指定' : null

  const fields = [
    field(milestone ? '里程碑名称' : '任务名称', title),
    field('目标', objective),
    field('说明', description),
    field('状态', status
      ? (milestone ? milestoneStatusLabel(status) : taskStatusLabel(status))
      : null),
    field('优先级', priority ? taskPriorityLabel(priority) : null),
    field('预计工时', estimateHours ? `${estimateHours} 小时` : null),
    field('开始日期', startDate ? formatDate(startDate) : null),
    field(milestone ? '目标日期' : '截止日期', dueDate ? formatDate(dueDate) : null),
    field('负责人', assigneeSpecified),
    field('所属里程碑', milestoneSpecified),
  ].filter((item): item is ApprovalField => item !== null)

  return {
    actionLabel,
    statusLabel: statusLabels[approval.status] ?? '未知状态',
    fields,
  }
}
