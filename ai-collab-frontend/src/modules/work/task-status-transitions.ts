import type { TaskStatus } from './types'

const TASK_STATUS_TRANSITIONS: Readonly<Record<TaskStatus, readonly TaskStatus[]>> = {
  TODO: ['IN_PROGRESS', 'BLOCKED', 'CANCELED'],
  IN_PROGRESS: ['TODO', 'BLOCKED', 'DONE', 'CANCELED'],
  BLOCKED: ['TODO', 'IN_PROGRESS', 'CANCELED'],
  DONE: ['IN_PROGRESS'],
  CANCELED: ['TODO'],
}

/**
 * 前端只展示后端状态机允许的目标状态，用于提前阻止明显无效的请求。
 * 后端 TaskStatusPolicy 仍是最终权限与业务规则边界；这里不能替代服务端校验。
 */
export function allowedTaskStatusTransitions(current: TaskStatus): readonly TaskStatus[] {
  return TASK_STATUS_TRANSITIONS[current]
}
