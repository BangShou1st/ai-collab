import type { AgentRunEvent } from './types'

export type ActivityStatus = 'running' | 'done' | 'failed' | 'waiting'
export type ActivityKind = 'read' | 'search' | 'analysis' | 'proposal' | 'approval' | 'success' | 'failure' | 'waiting' | 'info'

export interface AgentActivity {
  key: string
  tool: string
  kind: ActivityKind
  status: ActivityStatus
  title: string
  detail?: string | null
  durationMs?: number | null
  count?: number | null
  raw: AgentRunEvent[]
}

const TOOL_META: Record<string, { title: string; verb: string; kind: ActivityKind }> = {
  list_tasks: { title: '读取项目任务', verb: '正在读取项目任务', kind: 'read' },
  get_task: { title: '读取任务详情', verb: '正在读取任务详情', kind: 'read' },
  list_milestones: { title: '检查里程碑', verb: '正在检查里程碑', kind: 'read' },
  list_project_members: { title: '查看项目成员', verb: '正在查看项目成员', kind: 'read' },
  list_project_memories: { title: '读取项目记忆', verb: '正在读取项目记忆', kind: 'read' },
  get_project_overview: { title: '读取项目概览', verb: '正在读取项目概览', kind: 'read' },
  get_project_dashboard: { title: '读取项目仪表盘', verb: '正在读取项目仪表盘', kind: 'read' },
  check_project_progress: { title: '检查项目进度', verb: '正在检查项目进度', kind: 'read' },
  list_recent_audit_summaries: { title: '读取审计摘要', verb: '正在读取审计摘要', kind: 'read' },
  search_project_knowledge: { title: '搜索项目知识', verb: '正在搜索项目知识', kind: 'search' },
  answer_project_question_with_sources: { title: '问答检索', verb: '正在检索问答依据', kind: 'search' },
  analyze_project_risks: { title: '分析项目风险', verb: '正在分析项目风险', kind: 'analysis' },
  draft_weekly_report: { title: '起草周报', verb: '正在起草周报', kind: 'proposal' },
  create_task_after_approval: { title: '创建任务', verb: '正在准备创建任务', kind: 'proposal' },
  update_task_after_approval: { title: '更新任务', verb: '正在准备更新任务', kind: 'proposal' },
  create_milestone_after_approval: { title: '创建里程碑', verb: '正在准备创建里程碑', kind: 'proposal' },
  update_milestone_after_approval: { title: '更新里程碑', verb: '正在准备更新里程碑', kind: 'proposal' },
  create_memory_after_approval: { title: '创建记忆', verb: '正在准备创建记忆', kind: 'proposal' },
};

function toolOf(e: AgentRunEvent): string {
  const p = (e.payload ?? {}) as Record<string, unknown>
  const raw = [p.toolName, p.tool, p.name].find((v) => typeof v === 'string' && (v as string).length > 0) as string | undefined
  return (raw ?? 'unknown').trim() || 'unknown'
}

function callKey(e: AgentRunEvent): string {
  const p = (e.payload ?? {}) as Record<string, unknown>
  const raw = [p.callId, p.toolCallId, p.id].find((v) => typeof v === 'string' && (v as string).length > 0) as string | undefined
  return (raw ?? `${e.type}#${e.sequence}`).trim()
}

const TOOL_LIFECYCLE = new Set(['TOOL_CALL_PROPOSED', 'TOOL_CALL_STARTED', 'TOOL_CALL_COMPLETED', 'TOOL_CALL_FAILED'])
const APPROVAL_CLOSE = new Set(['APPROVAL_APPROVED', 'APPROVAL_REJECTED', 'APPROVAL_EXPIRED'])

/** Presentation policy: only tool lifecycles, approvals, analyzing and waiting become rows. Run/model/plan/context events drive state elsewhere and never render. Unknown future types are skipped, never shown. */
export function reduceAgentActivities(events: AgentRunEvent[]): AgentActivity[] {
  const tools = new Map<string, AgentRunEvent[]>()
  const toolOrder: string[] = []
  const approvals = new Map<string, AgentRunEvent[]>()
  const approvalOrder: string[] = []
  let analyzing: AgentRunEvent[] | null = null
  let waiting: AgentRunEvent[] | null = null
  for (const e of [...events].sort((a, b) => a.sequence - b.sequence)) {
    if (TOOL_LIFECYCLE.has(e.type)) {
      const key = callKey(e)
      if (!tools.has(key)) { tools.set(key, []); toolOrder.push(key) }
      tools.get(key)!.push(e)
    } else if (e.type === 'APPROVAL_REQUESTED' || APPROVAL_CLOSE.has(e.type)) {
      const key = approvalKey(e)
      if (!approvals.has(key)) { approvals.set(key, []); approvalOrder.push(key) }
      approvals.get(key)!.push(e)
    } else if (e.type === 'MODEL_STARTED' || e.type === 'MODEL_COMPLETED') {
      if (!analyzing) analyzing = []
      analyzing.push(e)
    } else if (e.type === 'WAITING_FOR_USER_INPUT') {
      if (!waiting) waiting = []
      waiting.push(e)
    }
  }
  const out: AgentActivity[] = []
  for (const key of toolOrder) out.push(buildToolActivity(key, tools.get(key)!))
  for (const key of approvalOrder) out.push(buildApprovalActivity(key, approvals.get(key)!))
  if (analyzing && analyzing.some((e) => e.type === 'MODEL_STARTED') && !analyzing.some((e) => e.type === 'MODEL_COMPLETED')) {
    out.push({ key: 'model:analyzing', tool: 'model', kind: 'analysis', status: 'running', title: '正在分析', detail: null, durationMs: null, count: null, raw: analyzing })
  }
  if (waiting) {
    out.push({ key: 'waiting:input', tool: 'input', kind: 'waiting', status: 'waiting', title: '等待你的输入', detail: null, durationMs: null, count: null, raw: waiting })
  }
  return out.sort((a, b) => minSequence(a.raw) - minSequence(b.raw))
}

function minSequence(list: AgentRunEvent[]): number {
  return Math.min(...list.map((e) => e.sequence))
}

function approvalKey(e: AgentRunEvent): string {
  const p = (e.payload ?? {}) as Record<string, unknown>
  const raw = [p.approvalId, p.id].find((v) => typeof v === 'string' && (v as string).length > 0) as string | undefined
  return (raw ?? `approval:${e.sequence}`).trim()
}

function buildToolActivity(key: string, list: AgentRunEvent[]): AgentActivity {
  const rawTool = toolOf(list[0])
  const known = rawTool !== 'unknown' && (TOOL_META[rawTool] !== undefined || /^[a-z][a-z0-9_]*$/.test(rawTool))
  const tool = known ? rawTool : 'tool'
  const meta = TOOL_META[tool]
  const title = meta?.title ?? '工具调用'
  const verb = meta?.verb ?? '正在调用工具'
  let status: ActivityStatus = 'running'
  let kind: ActivityKind = meta?.kind ?? 'info'
  let detail: string | null = null
  let durationMs: number | null = null
  let count: number | null = null
  for (const e of list) {
    const p = (e.payload ?? {}) as Record<string, unknown>
    if (typeof p.durationMs === 'number') durationMs = p.durationMs as number
    if (typeof p.latencyMs === 'number') durationMs = p.latencyMs as number
    if (typeof p.count === 'number') count = p.count as number
    if (typeof p.resultCount === 'number') count = p.resultCount as number
    if (typeof p.summary === 'string' && (p.summary as string).length > 0) detail = p.summary as string
    if (e.type === 'TOOL_CALL_COMPLETED') { status = 'done'; if (kind === 'info') kind = 'success' }
    if (e.type === 'TOOL_CALL_FAILED') { status = 'failed'; kind = 'failure' }
    if (typeof p.error === 'string' && (p.error as string).length > 0 && status === 'failed') detail = p.error as string
  }
  return { key: `tool:${key}`, tool, kind, status, title: status === 'running' ? verb : title, detail, durationMs, count, raw: list }
}

function buildApprovalActivity(key: string, list: AgentRunEvent[]): AgentActivity {
  const last = list[list.length - 1]
  const status: ActivityStatus = last.type === 'APPROVAL_REQUESTED' ? 'waiting' : (last.type === 'APPROVAL_APPROVED' ? 'done' : 'failed')
  return { key: `approval:${key}`, tool: 'approval', kind: 'approval', status, title: status === 'waiting' ? '等待审批' : (status === 'done' ? '已批准' : '已拒绝'), detail: null, durationMs: null, count: null, raw: list }
}
