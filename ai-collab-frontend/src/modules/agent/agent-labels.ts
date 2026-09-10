import type { AgentRunStatus } from './types'

export const RUN_STATUS_LABEL: Record<AgentRunStatus, string> = {
  CREATED: '已创建',
  QUEUED: '排队中',
  RUNNING: '运行中',
  WAITING_FOR_APPROVAL: '等待审批',
  WAITING_FOR_USER_INPUT: '等待你的输入',
  SUCCEEDED: '已完成',
  FAILED_RETRYABLE: '失败，可重试',
  FAILED: '失败',
  CANCELED: '已取消',
  BUDGET_EXCEEDED: '超出预算',
};

