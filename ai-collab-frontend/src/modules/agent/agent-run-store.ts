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
 * 周期权威对账：当 HTTP 快照序号不落后于时间线时整体同步；否则只单调前推步数、
 * 工具计数、token 用量及 QUEUED → RUNNING 迁移，不回退 SSE 已推进的终态或事件序号。
 */
export function reconcileRunProgress(
  state: AgentTimelineState,
  persisted: AgentRun,
  persistedSequence?: number,
): void {
  if (!state.run || state.run.id !== persisted.id) return
  const current = state.run
  const seq = persistedSequence ?? 0
  if (seq > state.lastSequence) {
    reconcileAgentRun(state, persisted)
    return
  }
  const canMoveStatusForward =
    (current.status === 'QUEUED' && persisted.status !== 'QUEUED')
    || (seq >= state.lastSequence
      && current.status !== 'QUEUED'
      && persisted.status !== 'QUEUED'
      && persisted.status !== 'RUNNING')
  if (canMoveStatusForward) {
    current.status = persisted.status
    current.errorCode = persisted.errorCode
  }
  current.stepsUsed = Math.max(current.stepsUsed, persisted.stepsUsed)
  current.toolCallsUsed = Math.max(current.toolCallsUsed, persisted.toolCallsUsed)
  current.inputTokensUsed = Math.max(current.inputTokensUsed, persisted.inputTokensUsed)
  current.outputTokensUsed = Math.max(current.outputTokensUsed, persisted.outputTokensUsed)
  if (typeof persisted.inputTokensActual === 'number') {
    current.inputTokensActual = Math.max(current.inputTokensActual ?? 0, persisted.inputTokensActual)
  }
  if (typeof persisted.outputTokensActual === 'number') {
    current.outputTokensActual = Math.max(current.outputTokensActual ?? 0, persisted.outputTokensActual)
  }
  if (typeof persisted.retryCount === 'number') {
    current.retryCount = Math.max(current.retryCount ?? 0, persisted.retryCount)
  }
}

function syncMetricsFromPayload(run: AgentRun, payload?: Record<string, unknown>): void {
  if (!payload) return
  if (typeof payload.stepsUsed === 'number') run.stepsUsed = Math.max(run.stepsUsed, payload.stepsUsed)
  if (typeof payload.toolCallsUsed === 'number') run.toolCallsUsed = Math.max(run.toolCallsUsed, payload.toolCallsUsed)
  if (typeof payload.inputTokensUsed === 'number') run.inputTokensUsed = Math.max(run.inputTokensUsed, payload.inputTokensUsed)
  if (typeof payload.outputTokensUsed === 'number') run.outputTokensUsed = Math.max(run.outputTokensUsed, payload.outputTokensUsed)
  if (typeof payload.inputTokensActual === 'number') run.inputTokensActual = Math.max(run.inputTokensActual ?? 0, payload.inputTokensActual)
  if (typeof payload.outputTokensActual === 'number') run.outputTokensActual = Math.max(run.outputTokensActual ?? 0, payload.outputTokensActual)
  if (typeof payload.tokenUsageEstimated === 'boolean') {
    (run as { tokenUsageEstimated?: boolean }).tokenUsageEstimated = payload.tokenUsageEstimated
  }
}

/**
 * 把一个持久事件应用到时间线；返回是否被接受（旧序号、重复序号或其他运行的事件被拒绝）。
 * 调用方据此决定衍生状态（如临时正文预览）是否推进——不接受的事件不得改变展示生命周期。
 */
export function applyAgentEvent(state: AgentTimelineState, event: AgentRunEvent): boolean {
  if (!state.run || event.runId !== state.run.id || event.sequence <= state.lastSequence) return false
  state.events.push(event)
  state.lastSequence = event.sequence
  syncMetricsFromPayload(state.run, event.payload)

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
  if (event.type === 'RUN_FAILED') {
    state.run.status = event.payload?.retryable === true ? 'FAILED_RETRYABLE' : 'FAILED'
    if (typeof event.payload?.errorCode === 'string' && event.payload.errorCode) {
      state.run.errorCode = event.payload.errorCode
    }
  }
  if (event.type === 'RUN_BUDGET_EXCEEDED') {
    state.run.status = 'BUDGET_EXCEEDED'
    if (typeof event.payload?.errorCode === 'string' && event.payload.errorCode) {
      state.run.errorCode = event.payload.errorCode
    }
  }
  return true
}
