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

function fallbackTitle(tool: string): string {
  return tool.split('_').filter(Boolean).join(' ');
}

export function reduceAgentActivities(events: AgentRunEvent[]): AgentActivity[] {
  const groups = new Map<string, AgentRunEvent[]>()
  const order: string[] = []
  for (const e of [...events].sort((a, b) => a.sequence - b.sequence)) {
    const isTool = e.type.startsWith('TOOL_CALL') || e.type === 'APPROVAL_REQUESTED'
    const key = isTool ? `tool:${callKey(e)}` : `evt:${e.sequence}`
    if (!groups.has(key)) { groups.set(key, []); order.push(key) }
    groups.get(key)!.push(e)
  }
  return order.map((key) => {
    const list = groups.get(key)!
    const first = list[0]
    const tool = toolOf(first)
    const meta = TOOL_META[tool]
    const title = meta?.title ?? fallbackTitle(tool)
    const verb = meta?.verb ?? `正在执行 ${fallbackTitle(tool)}`
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
      if (e.type === 'TOOL_CALL_COMPLETED' || e.type === 'APPROVAL_APPROVED' || e.type === 'RESULT_VERIFIED') { status = 'done'; if (kind === 'info') kind = 'success' }
      if (e.type === 'TOOL_CALL_FAILED' || e.type === 'APPROVAL_REJECTED' || e.type === 'APPROVAL_EXPIRED' || e.type === 'RUN_FAILED') { status = 'failed'; kind = 'failure' }
      if (e.type === 'APPROVAL_REQUESTED') { status = 'waiting'; kind = 'approval' }
      if (e.type === 'WAITING_FOR_USER_INPUT') { status = 'waiting'; kind = 'waiting' }
      if (typeof p.error === 'string' && (p.error as string).length > 0 && status === 'failed') detail = p.error as string
    }
    return { key, tool, kind, status, title: status === 'running' ? verb : title, detail, durationMs, count, raw: list }
  })
}

