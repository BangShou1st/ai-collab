import { computed, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { showApiError } from '../../api/api-result'
import { agentApi } from './agent-api'
import { projectApi } from '../project/project-api'
import type { ProjectMember } from '../project/types'
import { streamAgentEvents } from './agent-event-stream'
import { applyAgentEvent, emptyAgentTimeline, reconcileAgentRun, type AgentTimelineState } from './agent-run-store'
import { agentRunPresentation } from './agent-run-state'
import { reduceAgentActivities } from './agent-activity'
import { buildConversationBlocks, type ConversationBlock } from './conversation-blocks'
import { RUN_STATUS_LABEL } from './agent-labels'
import { useAuthStore } from '../../stores/auth-store'
import type { AgentApproval, AgentMessage, AgentPageContext, AgentPlanView, AgentRun, AgentRunDetail, AgentSession, AgentSessionSummary, AgentSkill } from './types'

/**
 * 项目协作 Agent 工作区的状态与业务逻辑。
 * 从 AgentView.vue 下沉：会话管理、SSE 事件流、上下文注入、审批与续答都在这里，
 * 视图层只负责渲染与编排。
 */
export function useAgentWorkspace() {
  const route = useRoute()
  const router = useRouter()
  const auth = useAuthStore()
  const projectId = computed(() => String(route.params.projectId ?? ''))
  const currentUserId = computed(() => String(auth.currentUser?.id ?? ''))
  const mobileView = ref<'sessions' | 'chat' | 'inspector'>('chat')
  const showInspector = ref(true)
  const pendingApprovals = computed(() => approvals.value.filter((x) => x.status === 'PENDING'))
  const resolvedApprovals = computed(() => approvals.value.filter((x) => x.status !== 'PENDING'))
  const sessions = ref<AgentSession[]>([])
  const summaries = ref<AgentSessionSummary[]>([])
  const sessionId = ref('')
  const messages = ref<AgentMessage[]>([])
  const approvals = ref<AgentApproval[]>([])
  const runDetail = ref<AgentRunDetail | null>(null)
  const activities = computed(() => reduceAgentActivities(timeline.value.events))
  const conversationBlocks = computed<ConversationBlock[]>(() =>
    buildConversationBlocks(messages.value, activeRun.value?.id ?? null, activities.value),
  )
  const summaryOf = (id: string) => {
    const summary = summaries.value.find((s) => s.id === id)
    const run = activeRun.value
    if (!summary || !run || run.sessionId !== id) return summary
    return { ...summary, latestRunId: run.id, latestRunStatus: run.status,
      latestActivityAt: timeline.value.events.at(-1)?.createdAt ?? summary.latestActivityAt }
  }
  const isCreator = (item: AgentSession) => !currentUserId.value || item.creatorId === currentUserId.value
  const relativeTime = (value: string | null | undefined) => {
    if (!value) return ''
    const ms = Date.now() - new Date(value).getTime()
    if (ms < 60_000) return '刚刚'
    if (ms < 3_600_000) return `${Math.floor(ms / 60_000)} 分钟前`
    if (ms < 86_400_000) return `${Math.floor(ms / 3_600_000)} 小时前`
    return new Date(value).toLocaleString('zh-CN')
  }
  const runStatusLabel = (status: string) => RUN_STATUS_LABEL[status as keyof typeof RUN_STATUS_LABEL] ?? status
  function evidenceTitle(item: unknown, fallback: string): string {
    if (item && typeof item === 'object') {
      const o = item as Record<string, unknown>
      for (const k of ['title', 'name', 'label', 'source', 'reason']) {
        if (typeof o[k] === 'string' && (o[k] as string).trim()) return (o[k] as string).trim()
      }
    }
    if (typeof item === 'string' && item.trim()) return item.trim().slice(0, 80)
    return fallback
  }
  function evidenceDetail(item: unknown): string | null {
    if (item && typeof item === 'object') {
      const o = item as Record<string, unknown>
      for (const k of ['url', 'content', 'text', 'summary', 'detail']) {
        if (typeof o[k] === 'string' && (o[k] as string).trim()) return (o[k] as string).trim().slice(0, 300)
      }
      return null
    }
    return null
  }
  const statusDot = (status: string | null | undefined) => {
    if (status === 'RUNNING' || status === 'QUEUED') return 'run'
    if (status === 'WAITING_FOR_APPROVAL' || status === 'WAITING_FOR_USER_INPUT') return 'wait'
    if (status === 'FAILED' || status === 'FAILED_RETRYABLE' || status === 'BUDGET_EXCEEDED') return 'fail'
    if (status === 'SUCCEEDED') return 'done'
    return 'idle'
  }
  const members = ref<ProjectMember[]>([])
  const skills = ref<AgentSkill[]>([])
  const selectedSkillCode = ref<string | null>(null)
  const question = ref('')
  const busy = ref(false)
  const sending = ref(false)
  const activeRun = ref<AgentRun | null>(null)
  const timeline = ref<AgentTimelineState>(emptyAgentTimeline())
  const activeRunState = computed(() => activeRun.value ? agentRunPresentation(activeRun.value) : null)
  const runTone = computed(() => {
    const severity = activeRunState.value?.severity
    return severity === 'error' ? 'danger' : (severity ?? 'info')
  })
  const removedContextKeys = ref(new Set<keyof AgentPageContext>())
  let timer: number | undefined
  let streamController: AbortController | undefined

  function fail(reason: unknown, action: string): void {
    showApiError(reason, action)
  }
  async function load() {
    if (!projectId.value) return
    busy.value = true
    try {
      const [s, sum, skillList, m] = await Promise.all([
        agentApi.sessions(projectId.value), agentApi.sessionSummaries(projectId.value),
        agentApi.skills(projectId.value), projectApi.listMembers(projectId.value),
      ])
      sessions.value = s.data; summaries.value = sum.data; skills.value = skillList.data; members.value = m.data
      if (sessionId.value && !sessions.value.some((x) => x.id === sessionId.value)) {
        sessionId.value = sessions.value[0]?.id ?? ''
      } else if (!sessionId.value && sessions.value[0]) {
        sessionId.value = sessions.value[0].id
      } else if (sessionId.value) {
        await restoreSession(sessionId.value)
      }
    } catch (reason) { fail(reason, 'Agent 工作区加载') } finally { busy.value = false }
  }
  async function loadMessages() {
    messages.value = sessionId.value
      ? (await agentApi.messages(projectId.value, sessionId.value)).data : []
  }
  function toPlanView(plan: unknown): AgentPlanView | null {
    if (!plan || typeof plan !== 'object') return null
    const p = plan as Record<string, unknown>
    const steps = Array.isArray(p.steps) ? (p.steps as Record<string, unknown>[]).map((s, i) => ({
      id: typeof s.id === 'string' ? s.id : `step-${i}`,
      title: typeof s.title === 'string' ? s.title : `步骤 ${i + 1}`,
      purpose: typeof s.purpose === 'string' ? s.purpose : undefined,
      status: typeof s.status === 'string' ? s.status : 'PENDING',
    })) : []
    return {
      version: typeof p.version === 'number' ? p.version : 1,
      objective: typeof p.objective === 'string' ? p.objective : (typeof p.goal === 'string' ? p.goal : ''),
      steps,
      successCriteria: Array.isArray(p.successCriteria) ? (p.successCriteria as unknown[]).map(String) : undefined,
    }
  }
  let restoreSeq = 0
  async function restoreSession(id: string) {
    const token = ++restoreSeq
    const pid = projectId.value
    const fresh = () => token === restoreSeq && projectId.value === pid && sessionId.value === id
    streamController?.abort()
    activeRun.value = null
    runDetail.value = null
    approvals.value = []
    timeline.value = emptyAgentTimeline()
    const [msgs, latest] = await Promise.all([
      agentApi.messages(pid, id),
      agentApi.latestRun(pid, id),
    ])
    if (!fresh()) return
    messages.value = msgs.data
    runDetail.value = latest.data
    const run = latest.data?.run
    if (!run) return
    activeRun.value = run
    timeline.value = emptyAgentTimeline(run)
    timeline.value.plan = toPlanView(latest.data?.plan)
    try {
      const history = (await agentApi.runEvents(pid, run.id, 0)).data
      if (!fresh()) return
      for (const e of history) applyAgentEvent(timeline.value, e)
      const planEvent = [...history].reverse().find((e) => e.type === 'PLAN_CREATED' || e.type === 'PLAN_UPDATED')
      const planFromEvents = toPlanView((planEvent?.payload as Record<string, unknown> | undefined)?.plan)
      if (planFromEvents) timeline.value.plan = planFromEvents
    } catch (reason) { if (fresh()) fail(reason, 'Agent 历史恢复') }
    if (!fresh()) return
    timeline.value.lastSequence = Math.max(latest.data?.lastEventSequence ?? 0, timeline.value.lastSequence)
    if (['SUCCEEDED', 'FAILED', 'FAILED_RETRYABLE', 'CANCELED', 'BUDGET_EXCEEDED'].includes(run.status))
      reconcileAgentRun(timeline.value, run)
    activeRun.value = timeline.value.run
    if (!fresh()) return
    await refreshApprovals({ projectId: pid, sessionId: id, runId: run.id, token })
    if (!fresh()) return
    if (run.status === 'RUNNING' || run.status === 'QUEUED') {
      resumeEventStream()
    }
  }
  function resumeEventStream() {
    if (!activeRun.value) return
    streamController?.abort()
    streamController = new AbortController()
    void consumeEventStream(activeRun.value.id, streamController, 250)
  }
  async function newSession() {
    try {
      const created = (await agentApi.createSession(projectId.value, `项目协作 ${new Date().toLocaleDateString('zh-CN')}`)).data
      sessions.value.unshift(created); sessionId.value = created.id
    } catch (reason) { fail(reason, 'Agent 会话创建') }
  }
  async function renameSession(item: AgentSession) {
    try {
      const result = await ElMessageBox.prompt('请输入新的会话名称', '重命名会话', {
        inputValue: item.title,
        inputValidator: value => {
          const title = value.trim()
          if (!title) return '会话名称不能为空'
          if (Array.from(title).length > 160) return '会话名称不能超过 160 个字符'
          return true
        },
        confirmButtonText: '保存',
        cancelButtonText: '取消',
      })
      const title = result.value.trim()
      const updated = (await agentApi.renameSession(projectId.value, item.id, title)).data
      sessions.value = sessions.value.map(session =>
        session.id === item.id ? { ...session, ...updated } : session)
      ElMessage.success('会话已重命名')
    } catch (reason) {
      if (reason === 'cancel' || reason === 'close') return
      fail(reason, 'Agent 会话重命名')
    }
  }
  async function deleteSession(item: AgentSession) {
    try {
      await ElMessageBox.confirm(
        `确认删除会话"${item.title}"及其全部消息吗？`,
        '删除会话',
        {
          type: 'warning',
          confirmButtonText: '删除',
          cancelButtonText: '取消',
        },
      )
      await agentApi.deleteSession(projectId.value, item.id)
      sessions.value = sessions.value.filter(session => session.id !== item.id)
      if (sessionId.value === item.id) {
        activeRun.value = null
        messages.value = []
        sessionId.value = sessions.value[0]?.id ?? ''
      }
      ElMessage.success('会话已删除')
    } catch (reason) {
      if (reason === 'cancel' || reason === 'close') return
      fail(reason, 'Agent 会话删除')
    }
  }
  async function send() {
    const content = question.value.trim()
    if (!content || sending.value) return
    if (!sessionId.value) await newSession()
    if (!sessionId.value) return
    sending.value = true
    try {
      const run = (await agentApi.submit(projectId.value, sessionId.value, {
        content,
        skillCode: selectedSkillCode.value,
        pageContext: currentPageContext(),
      })).data
      ++restoreSeq
      activeRun.value = run
      runDetail.value = null
      timeline.value = emptyAgentTimeline(run)
      question.value = ''
      selectedSkillCode.value = null
      await loadMessages()
      startEventStream(run)
    } catch (reason) { fail(reason, 'Agent 消息发送') } finally { sending.value = false }
  }
  function queryId(key: string): string | null {
    const value = route.query[key]
    const raw = Array.isArray(value) ? value[0] : value
    return typeof raw === 'string' && /^[0-9a-f-]{36}$/i.test(raw) ? raw : null
  }
  function currentPageContext(): AgentPageContext {
    const paramId = (key: string) => {
      const value = route.params[key]
      return typeof value === 'string' && /^[0-9a-f-]{36}$/i.test(value) ? value : null
    }
    return {
      route: String(route.name ?? route.path).slice(0, 80),
      selectedTaskId: removedContextKeys.value.has('selectedTaskId') ? null : (queryId('task') ?? paramId('taskId')),
      selectedMilestoneId: removedContextKeys.value.has('selectedMilestoneId') ? null : (queryId('milestone') ?? paramId('milestoneId')),
      selectedDocumentId: removedContextKeys.value.has('selectedDocumentId') ? null : (queryId('document') ?? paramId('documentId')),
      selectedPlanId: removedContextKeys.value.has('selectedPlanId') ? null : (queryId('plan') ?? paramId('planId')),
      filters: {},
    }
  }
  function dropQueryKey(key: string) {
    const next = { ...route.query }
    delete next[key]
    void router.replace({ query: next })
  }
  const pageContext = computed(currentPageContext)
  const hasPageContext = computed(() => {
    const c = pageContext.value
    return Boolean(c.selectedTaskId ?? c.selectedMilestoneId ?? c.selectedDocumentId ?? c.selectedPlanId)
  })
  function removeContext(key: keyof AgentPageContext) {
    removedContextKeys.value = new Set([...removedContextKeys.value, key])
    const queryKey = key === 'selectedTaskId' ? 'task' : key === 'selectedDocumentId' ? 'document' : key === 'selectedPlanId' ? 'plan' : key === 'selectedMilestoneId' ? 'milestone' : null
    if (queryKey) dropQueryKey(queryKey)
  }
  function clearContext() {
    removedContextKeys.value = new Set(['selectedTaskId', 'selectedMilestoneId', 'selectedDocumentId', 'selectedPlanId'])
    const next = { ...route.query }
    delete next.task
    delete next.document
    delete next.plan
    delete next.milestone
    void router.replace({ query: next })
  }
  function startEventStream(run: AgentRun) {
    streamController?.abort()
    streamController = new AbortController()
    timeline.value = emptyAgentTimeline(run)
    void consumeEventStream(run.id, streamController, 250)
  }
  async function consumeEventStream(runId: string, controller: AbortController, delayMs: number) {
    const fresh = () => !controller.signal.aborted && timeline.value.run?.id === runId
    try {
      if (!fresh()) return
      timeline.value.connected = true
      await streamAgentEvents(
        `/api/v1/projects/${projectId.value}/agent/runs/${runId}/events`,
        timeline.value.lastSequence,
        controller.signal,
        event => {
          if (!fresh()) return
          applyAgentEvent(timeline.value, event)
          activeRun.value = timeline.value.run
        },
      )
      if (!fresh()) return
      timeline.value.connected = false
      if (!controller.signal.aborted) {
        const persisted = (await agentApi.run(projectId.value, runId)).data
        if (!fresh()) return
        runDetail.value = persisted
        reconcileAgentRun(timeline.value, persisted.run)
        activeRun.value = timeline.value.run
      }
      if (controller.signal.aborted || timeline.value.run?.status === 'SUCCEEDED'
        || timeline.value.run?.status === 'FAILED' || timeline.value.run?.status === 'CANCELED'
        || timeline.value.run?.status === 'BUDGET_EXCEEDED'
        || timeline.value.run?.status === 'WAITING_FOR_APPROVAL'
        || timeline.value.run?.status === 'WAITING_FOR_USER_INPUT') {
        await Promise.all([
          loadMessages(),
          refreshApprovals({ projectId: projectId.value, sessionId: sessionId.value, runId }),
        ])
        return
      }
      await new Promise(resolve => window.setTimeout(resolve, delayMs))
      if (!controller.signal.aborted) void consumeEventStream(runId, controller, Math.min(delayMs * 2, 5000))
    } catch (reason) {
      timeline.value.connected = false
      if (controller.signal.aborted) return
      await new Promise(resolve => window.setTimeout(resolve, delayMs))
      if (!controller.signal.aborted) void consumeEventStream(runId, controller, Math.min(delayMs * 2, 5000))
    }
  }
  interface ApprovalScope {
    projectId: string
    sessionId: string
    runId: string
    token?: number
  }

  function currentApprovalScope(): ApprovalScope | null {
    const runId = activeRun.value?.id
    if (!runId) return null
    return { projectId: projectId.value, sessionId: sessionId.value, runId }
  }

  async function refreshApprovals(scope?: ApprovalScope) {
    const pid = scope?.projectId ?? projectId.value
    const sid = scope?.sessionId ?? sessionId.value
    const rid = scope?.runId ?? activeRun.value?.id
    if (!rid) {
      if (projectId.value === pid && sessionId.value === sid) approvals.value = []
      return
    }
    // Fetch into a local first; attribute the response only to still-current context.
    // Never re-read activeRun to decide an old response's ownership.
    const data = (await agentApi.runApprovals(pid, rid)).data
    if (projectId.value !== pid || sessionId.value !== sid) return
    if (scope?.token !== undefined && scope.token !== restoreSeq) return
    if ((activeRun.value?.id ?? null) !== rid) return
    approvals.value = data
  }
  /** 正在提交的审批操作（卡片 ID + 动作）；打开弹窗前即占用，取消、失败、成功统一释放，防止重复进入。 */
  const approvalBusy = ref<{ id: string; action: 'approve' | 'reject' } | null>(null)
  /**
   * 审批失败后拉取服务端最新审批状态：提案已修订但当前版本仍待审批时，
   * 待审批列表展示最新版本供重新确认；已批准、已拒绝、已过期等
   * 不再待审批的记录自动移出待审批列表。刷新失败不能覆盖原始审批错误。
   */
  async function refreshApprovalsAfterFailure() {
    const scope = currentApprovalScope()
    try {
      if (scope) await refreshApprovals(scope)
      else await refreshApprovals()
    } catch (refreshReason) { fail(refreshReason, 'Agent 审批状态刷新') }
  }
  async function approve(item: AgentApproval) {
    if (approvalBusy.value) return
    approvalBusy.value = { id: item.id, action: 'approve' }
    try {
      try {
        await ElMessageBox.confirm('确认按此差异写入项目数据？操作将记录审批人和结果。', '批准 Agent 提案', { type: 'warning' })
      } catch { return }
      await agentApi.approve(projectId.value, item)
      await restoreSession(sessionId.value)
      ElMessage.success('已批准并执行')
    }
    catch (reason) {
      fail(reason, 'Agent 提案批准')
      await refreshApprovalsAfterFailure()
    }
    finally { approvalBusy.value = null }
  }
  async function continueRun(content: string) {
    if (!activeRun.value || activeRun.value.status !== 'WAITING_FOR_USER_INPUT') return
    sending.value = true
    try {
      await agentApi.continueRun(projectId.value, activeRun.value.id, content)
      question.value = ''
      await restoreSession(sessionId.value)
    } catch (reason) { fail(reason, 'Agent 回复') } finally { sending.value = false }
  }
  function continueRunHandler() {
    const content = question.value.trim()
    if (!content || sending.value) return
    continueRun(content)
  }
  async function retryActiveRun() {
    if (!activeRun.value || sending.value) return
    const sourceRunId = activeRun.value.id
    // 记录请求作用域：响应返回时用户可能已切换项目/会话，或页面已被其他恢复重建；
    // 迟到响应不得覆盖已切换的视图、不得启动不属于当前作用域的事件订阅
    const requestedProjectId = projectId.value
    const requestedSessionId = sessionId.value
    const requestedToken = restoreSeq
    sending.value = true
    try {
      const run = (await agentApi.retry(requestedProjectId, sourceRunId)).data
      if (projectId.value !== requestedProjectId || sessionId.value !== requestedSessionId
        || restoreSeq !== requestedToken) {
        return
      }
      if (run.id !== sourceRunId) {
        // 终态重新尝试返回新运行：切换过去并恢复事件订阅；旧运行记录保留可回看
        ++restoreSeq
        activeRun.value = run
        runDetail.value = null
        startEventStream(run)
        await loadMessages()
      } else {
        // 运行内自动重试路径：原地恢复当前会话状态
        await restoreSession(sessionId.value)
      }
    } catch (reason) { fail(reason, 'Agent 重试') } finally { sending.value = false }
  }
  async function cancelActiveRun() {
    if (!activeRun.value) return
    try {
      await agentApi.cancel(projectId.value, activeRun.value.id)
      ElMessage.success('已请求取消')
    } catch (reason) { fail(reason, 'Agent 取消') }
  }
  async function reject(item: AgentApproval) {
    if (approvalBusy.value) return
    approvalBusy.value = { id: item.id, action: 'reject' }
    try {
      let reason_text: string
      try {
        const result = await ElMessageBox.prompt('请输入拒绝原因', '拒绝 Agent 提案', { inputValidator: value => Boolean(value.trim()) })
        reason_text = result.value
      } catch { return }
      const scope = currentApprovalScope()
      await agentApi.reject(projectId.value, item, reason_text)
      if (scope) await refreshApprovals(scope)
      else await refreshApprovals()
    }
    catch (reason) {
      fail(reason, 'Agent 提案拒绝')
      await refreshApprovalsAfterFailure()
    }
    finally { approvalBusy.value = null }
  }
  const time = (value: string) => new Date(value).toLocaleString('zh-CN')
  watch(projectId, (next, prev) => {
    if (!next) return
    if (prev !== undefined && next !== prev) {
      streamController?.abort()
      sessionId.value = ''
      messages.value = []
      activeRun.value = null
      runDetail.value = null
      approvals.value = []
      timeline.value = emptyAgentTimeline()
    }
    void load()
  }, { immediate: true })
  watch(sessionId, (id) => {
    if (!id) return
    restoreSession(id).catch(reason => fail(reason, 'Agent 会话恢复'))
  })
  onUnmounted(() => { window.clearTimeout(timer); streamController?.abort() })

  return {
    projectId, currentUserId, mobileView, showInspector, runTone,
    pendingApprovals, resolvedApprovals, sessions, summaries, sessionId, messages,
    approvals, runDetail, activities, conversationBlocks,
    summaryOf, isCreator, relativeTime, runStatusLabel, evidenceTitle, evidenceDetail,
    statusDot, members, skills, selectedSkillCode, question, busy, sending,
    activeRun, timeline, activeRunState, pageContext, hasPageContext,
    load, newSession, renameSession, deleteSession, send, removeContext, clearContext,
    approve, reject, approvalBusy, continueRunHandler, retryActiveRun, cancelActiveRun, time,
  }
}
