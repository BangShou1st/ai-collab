import type { AgentPlanView, AgentRun, AgentRunEvent } from './types'

export interface AgentTimelineState {
  run: AgentRun | null
  events: AgentRunEvent[]
  lastSequence: number
  plan: AgentPlanView | null
  connected: boolean
}

export function emptyAgentTimeline(run: AgentRun | null = null): AgentTimelineState {
  return { run: run ? { ...run } : null, events: [], lastSequence: 0, plan: null, connected: false }
}

export function reconcileAgentRun(state: AgentTimelineState, persisted: AgentRun): void {
  if (!state.run || state.run.id !== persisted.id) return
  state.run = persisted
}

/**
 * 把一个持久事件应用到时间线；返回是否被接受（旧序号、重复序号或其他运行的事件被拒绝）。
 * 调用方据此决定衍生状态（如临时正文预览）是否推进——不接受的事件不得改变展示生命周期。
 */
export function applyAgentEvent(state: AgentTimelineState, event: AgentRunEvent): boolean {
  if (!state.run || event.runId !== state.run.id || event.sequence <= state.lastSequence) return false
  state.events.push(event)
  state.lastSequence = event.sequence

  if (event.type === 'PLAN_CREATED' || event.type === 'PLAN_UPDATED') {
    state.plan = event.payload as unknown as AgentPlanView
  }
  if (event.type === 'MODEL_STARTED' || event.type === 'TOOL_CALL_STARTED') state.run.status = 'RUNNING'
  if (event.type === 'APPROVAL_REQUESTED' && event.payload?.status !== 'RUNNING') state.run.status = 'WAITING_FOR_APPROVAL'
  // APPROVAL_UPDATED 不改变 Run 状态，审批与 Run 已解耦
  if (event.type === 'WAITING_FOR_USER_INPUT') state.run.status = 'WAITING_FOR_USER_INPUT'
  if (event.type === 'RUN_RETRY_SCHEDULED') state.run.status = 'QUEUED'
  // 暂停控制事件：RUNNING 上的意图先呈现"正在暂停"（状态仍 RUNNING，等待边界确认）
  if (event.type === 'RUN_PAUSE_REQUESTED') state.run.pauseRequestedAt = event.createdAt
  if (event.type === 'RUN_PAUSED') {
    state.run.status = 'PAUSED'
    state.run.pauseRequestedAt = event.createdAt
  }
  if (event.type === 'RUN_RESUMED') {
    state.run.status = 'QUEUED'
    state.run.pauseRequestedAt = null
  }
  if (event.type === 'RUN_CANCELED') state.run.status = 'CANCELED'
  if (event.type === 'RUN_SUCCEEDED') state.run.status = 'SUCCEEDED'
  if (event.type === 'RUN_FAILED') state.run.status = event.payload?.retryable === true ? 'FAILED_RETRYABLE' : 'FAILED'
  if (event.type === 'RUN_BUDGET_EXCEEDED') state.run.status = 'BUDGET_EXCEEDED'
  return true
}
