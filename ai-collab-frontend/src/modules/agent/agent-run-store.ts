import type { AgentPlanView, AgentRun, AgentRunEvent } from './types'

export interface AgentTimelineState {
  run: AgentRun | null
  events: AgentRunEvent[]
  lastSequence: number
  plan: AgentPlanView | null
  connected: boolean
}

export function emptyAgentTimeline(run: AgentRun | null = null): AgentTimelineState {
  return { run, events: [], lastSequence: 0, plan: null, connected: false }
}

export function reconcileAgentRun(state: AgentTimelineState, persisted: AgentRun): void {
  if (!state.run || state.run.id !== persisted.id) return
  state.run = persisted
}

export function applyAgentEvent(state: AgentTimelineState, event: AgentRunEvent): void {
  if (!state.run || event.runId !== state.run.id || event.sequence <= state.lastSequence) return
  state.events.push(event)
  state.lastSequence = event.sequence

  if (event.type === 'PLAN_CREATED' || event.type === 'PLAN_UPDATED') {
    state.plan = event.payload as unknown as AgentPlanView
  }
  if (event.type === 'MODEL_STARTED' || event.type === 'TOOL_CALL_STARTED') state.run.status = 'RUNNING'
  if (event.type === 'APPROVAL_REQUESTED') state.run.status = 'WAITING_FOR_APPROVAL'
  if (event.type === 'RUN_RETRY_SCHEDULED') state.run.status = 'QUEUED'
  if (event.type === 'RUN_CANCELED') state.run.status = 'CANCELED'
  if (event.type === 'RUN_SUCCEEDED') state.run.status = 'SUCCEEDED'
  if (event.type === 'RUN_FAILED') state.run.status = 'FAILED'
  if (event.type === 'RUN_BUDGET_EXCEEDED') state.run.status = 'BUDGET_EXCEEDED'
}
