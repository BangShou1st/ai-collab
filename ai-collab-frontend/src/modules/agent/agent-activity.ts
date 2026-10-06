import type { AgentRunEvent } from './types'

export type ActivityStatus = 'running' | 'done' | 'failed' | 'waiting'
export type ActivityKind = 'read' | 'search' | 'analysis' | 'proposal' | 'approval' | 'success' | 'failure' | 'waiting' | 'info' | 'narration'

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
  create_task_after_approval: { title: '任务创建提案', verb: '正在准备创建任务', kind: 'proposal' },
  update_task_after_approval: { title: '任务更新提案', verb: '正在准备更新任务', kind: 'proposal' },
  create_milestone_after_approval: { title: '里程碑创建提案', verb: '正在准备创建里程碑', kind: 'proposal' },
  update_milestone_after_approval: { title: '里程碑更新提案', verb: '正在准备更新里程碑', kind: 'proposal' },
  create_memory_after_approval: { title: '记忆创建提案', verb: '正在准备创建记忆', kind: 'proposal' },
};

function toolOf(e: AgentRunEvent): string {
  const p = (e.payload ?? {}) as Record<string, unknown>
  const raw = [p.toolName, p.tool, p.name].find((v) => typeof v === 'string' && (v as string).length > 0) as string | undefined
  return (raw ?? 'unknown').trim() || 'unknown'
}

function callKey(e: AgentRunEvent): string {
  const p = (e.payload ?? {}) as Record<string, unknown>
  const raw = [p.invocationId, p.callId, p.toolCallId, p.id].find((v) => typeof v === 'string' && (v as string).length > 0) as string | undefined
  return (raw ?? `${e.type}#${e.sequence}`).trim()
}

const TOOL_LIFECYCLE = new Set(['TOOL_CALL_PROPOSED', 'TOOL_CALL_STARTED', 'TOOL_CALL_COMPLETED', 'TOOL_CALL_FAILED'])
const APPROVAL_CLOSE = new Set(['APPROVAL_APPROVED', 'APPROVAL_REJECTED', 'APPROVAL_EXPIRED'])

/** Presentation policy: tool lifecycles, approvals, turn narrations, analyzing and waiting become rows.
 *  Run/model/plan/context events drive state elsewhere. MODEL_COMPLETED narration only exists for
 *  turns that also requested tools (toolCallCount > 0); a text-only final turn is the ASSISTANT
 *  message and must not be duplicated here. Unknown future types are skipped, never shown. */
export function reduceAgentActivities(events: AgentRunEvent[]): AgentActivity[] {
  const terminal = [...events].reverse().find(event => ['RUN_CANCELED', 'RUN_SUCCEEDED', 'RUN_FAILED', 'RUN_BUDGET_EXCEEDED'].includes(event.type))
  const tools = new Map<string, AgentRunEvent[]>()
  const toolOrder: string[] = []
  const approvals = new Map<string, AgentRunEvent[]>()
  const approvalOrder: string[] = []
  const narrations: AgentRunEvent[] = []
  const controlNotes: AgentRunEvent[] = []
  // “正在分析”按最新一次模型请求判定：只比较最新 MODEL_STARTED 与最新 MODEL_COMPLETED 的序号，
  // 不能因为历史上出现过任何一次完成就永久抑制后续轮次的在途状态。
  let latestModelStarted: AgentRunEvent | null = null
  let latestModelCompleted: AgentRunEvent | null = null
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
      if (!latestModelStarted || e.sequence >= latestModelStarted.sequence) latestModelStarted = e
      if (e.type === 'MODEL_COMPLETED') {
        const p = (e.payload ?? {}) as Record<string, unknown>
        const content = typeof p.content === 'string' ? p.content.trim() : ''
        const toolCount = typeof p.toolCallCount === 'number' ? p.toolCallCount : 0
        if (toolCount > 0 && content) narrations.push(e)
        if (!latestModelCompleted || e.sequence >= latestModelCompleted.sequence) latestModelCompleted = e
      }
    } else if (e.type === 'RUN_PAUSED' || e.type === 'RUN_RESUMED') {
      // 暂停/继续是控制状态变化，不是用户任务或最终回答，只渲染一行状态说明
      controlNotes.push(e)
    } else if (e.type === 'WAITING_FOR_USER_INPUT') {
      if (!waiting) waiting = []
      waiting.push(e)
    }
  }
  const out: AgentActivity[] = []
  for (const key of toolOrder) {
    const activity = buildToolActivity(key, tools.get(key)!)
    if (terminal && activity.status === 'running') {
      activity.status = 'failed'
      activity.kind = 'failure'
      activity.title = TOOL_META[activity.tool]?.title ?? '工具调用'
      activity.detail = terminal.type === 'RUN_CANCELED' ? '已取消' : '运行已结束，工具结果需核对'
    }
    out.push(activity)
  }
  for (const e of narrations) {
    const p = (e.payload ?? {}) as Record<string, unknown>
    out.push({ key: `narration:${e.sequence}`, tool: 'narration', kind: 'narration', status: 'done', title: 'Agent', detail: (p.content as string).trim(), durationMs: null, count: null, raw: [e] })
  }
  for (const key of approvalOrder) out.push(buildApprovalActivity(key, approvals.get(key)!))
  for (const e of controlNotes) {
    out.push({ key: `control:${e.sequence}`, tool: 'control', kind: 'info', status: 'done',
      title: e.type === 'RUN_RESUMED' ? '已继续' : '已暂停，进度已保留',
      detail: null, durationMs: null, count: null, raw: [e] })
  }
  const requestInFlight = latestModelStarted !== null
    && (latestModelCompleted === null || latestModelStarted.sequence > latestModelCompleted.sequence)
  if (!terminal && requestInFlight && latestModelStarted) {
    const model = (latestModelStarted.payload as Record<string, unknown> | undefined)?.model
    out.push({ key: 'model:analyzing', tool: 'model', kind: 'analysis', status: 'running', title: '正在分析', detail: typeof model === 'string' && model ? `使用 ${model}` : null, durationMs: null, count: null, raw: [latestModelStarted] })
  }
  if (waiting && !terminal) {
    out.push({ key: 'waiting:input', tool: 'input', kind: 'waiting', status: 'waiting', title: '等待你的输入', detail: null, durationMs: null, count: null, raw: waiting })
  }
  return out.sort((a, b) => minSequence(a.raw) - minSequence(b.raw))
}

export interface AgentActivityGroup {
  key: string
  kind: 'group'
  title: string
  status: ActivityStatus
  count: number
  items: AgentActivity[]
}
export type ConversationActivity = AgentActivity | AgentActivityGroup

/** Merge runs of ≥3 consecutive completed read/search rows into one collapsible group;
 *  narration, failures, running and approval rows always stay individually visible. */
export function groupAgentActivities(items: AgentActivity[]): ConversationActivity[] {
  const out: ConversationActivity[] = []
  let bucket: AgentActivity[] = []
  const flush = () => {
    if (bucket.length >= 3) {
      const titles = [...new Set(bucket.map((item) => item.title))]
      out.push({
        key: `group:${bucket[0].key}`,
        kind: 'group',
        title: titles.length === 1 ? titles[0] : '查阅项目资料',
        status: 'done',
        count: bucket.length,
        items: bucket,
      })
    } else out.push(...bucket)
    bucket = []
  }
  for (const item of items) {
    if (item.kind === 'narration') { flush(); out.push(item); continue }
    if (item.status === 'done' && (item.kind === 'read' || item.kind === 'search')) { bucket.push(item); continue }
    flush(); out.push(item)
  }
  flush()
  return out
}

/** Last known model identity for the active run: actual provider response wins,
 *  then the in-flight MODEL_STARTED, then the run detail snapshot. */
export function currentModelFromEvents(events: AgentRunEvent[]): { provider: string; model: string; live: boolean } | null {
  for (let i = events.length - 1; i >= 0; i--) {
    const e = events[i]
    if (e.type !== 'MODEL_COMPLETED' && e.type !== 'MODEL_STARTED') continue
    const p = (e.payload ?? {}) as Record<string, unknown>
    if (typeof p.model === 'string' && p.model.trim()) {
      return { provider: typeof p.provider === 'string' ? p.provider : '', model: p.model, live: e.type === 'MODEL_STARTED' }
    }
  }
  return null
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
    if (e.type === 'TOOL_CALL_FAILED' && typeof p.status === 'string') {
      const labels: Record<string, string> = { FAILED: '执行失败', REJECTED: '调用被拒绝', CANCELED: '已取消', SKIPPED: '未执行', UNKNOWN: '结果未知，需核对' }
      detail = `${labels[p.status] ?? '执行失败'}${typeof p.errorCode === 'string' && p.errorCode ? `：${p.errorCode}` : ''}`
    }
    if (typeof p.error === 'string' && (p.error as string).length > 0 && status === 'failed') detail = p.error as string
  }
  return { key: `tool:${key}`, tool, kind, status, title: status === 'running' ? verb : title, detail, durationMs, count, raw: list }
}

function buildApprovalActivity(key: string, list: AgentRunEvent[]): AgentActivity {
  const last = list[list.length - 1]
  const status: ActivityStatus = last.type === 'APPROVAL_REQUESTED' ? 'waiting' : (last.type === 'APPROVAL_APPROVED' ? 'done' : 'failed')
  return { key: `approval:${key}`, tool: 'approval', kind: 'approval', status, title: status === 'waiting' ? '等待审批' : (status === 'done' ? '已批准' : '已拒绝'), detail: null, durationMs: null, count: null, raw: list }
}
