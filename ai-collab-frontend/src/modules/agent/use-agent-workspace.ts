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
import { reduceAgentActivities, currentModelFromEvents } from './agent-activity'
import { buildConversationBlocks, type ConversationBlock } from './conversation-blocks'
import { RUN_STATUS_LABEL } from './agent-labels'
import { useAuthStore } from '../../stores/auth-store'
import type { AgentApproval, AgentContentFrame, AgentMessage, AgentPageContext, AgentPlanView, AgentRun, AgentRunDetail, AgentRunEvent, AgentSession, AgentSessionSummary, AgentSkill } from './types'

/** 正文预览展示上限：到限仅停止追加展示，不影响后台生成与持久化。 */
const CONTENT_PREVIEW_MAX_CHARS = 8000

export interface ContentPreviewState {
  runId: string
  modelCallId: string
  text: string
  revision: number
  truncated: boolean
  /** 该请求的 MODEL_COMPLETED 已到达（纯文本轮等待最终消息落库后收口） */
  finalized: boolean
}

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
  // 当前实际使用的模型：模型轮次事件优先（换模型后下一轮即更新），回退到运行详情快照
  const activeModel = computed(() => currentModelFromEvents(timeline.value.events)
    ?? (runDetail.value?.modelConfiguration
      ? { provider: runDetail.value.modelConfiguration.provider, model: runDetail.value.modelConfiguration.model, live: false }
      : null))
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
    if (status === 'PAUSED' || status === 'WAITING_FOR_APPROVAL' || status === 'WAITING_FOR_USER_INPUT') return 'wait'
    if (status === 'FAILED' || status === 'FAILED_RETRYABLE' || status === 'BUDGET_EXCEEDED') return 'fail'
    if (status === 'SUCCEEDED') return 'done'
    return 'idle'
  }
  // 暂停请求已发出、服务端尚未确认（RUNNING + pauseRequestedAt）：界面区分"正在暂停"与"已暂停"
  const pausePending = computed(() => {
    const run = activeRun.value
    return Boolean(run && run.status === 'RUNNING' && run.pauseRequestedAt)
  })
  const canPause = computed(() => {
    const run = activeRun.value
    return Boolean(run && !pausePending.value
      && (run.status === 'QUEUED' || run.status === 'RUNNING' || run.status === 'FAILED_RETRYABLE'))
  })
  const members = ref<ProjectMember[]>([])
  const skills = ref<AgentSkill[]>([])
  const selectedSkillCode = ref<string | null>(null)
  const question = ref('')
  const busy = ref(false)
  const sending = ref(false)
  const timeline = ref<AgentTimelineState>(emptyAgentTimeline())
  const activeRun = computed(() => timeline.value.run)
  // 当前请求的临时正文预览：由 MODEL_STARTED(modelCallId) 开启，按累计快照帧幂等替换，
  // 由请求结束事件或最终消息落库收口；不参与时间线/持久事件，切会话/切运行即丢弃
  const contentPreview = ref<ContentPreviewState | null>(null)
  function applyPreviewEvent(event: AgentRunEvent) {
    const payload = (event.payload ?? {}) as Record<string, unknown>
    if (event.type === 'MODEL_STARTED') {
      const modelCallId = typeof payload.modelCallId === 'string' ? payload.modelCallId : null
      if (modelCallId && timeline.value.run) {
        contentPreview.value = {
          runId: timeline.value.run.id, modelCallId,
          text: '', revision: 0, truncated: false, finalized: false,
        }
      }
      return
    }
    const preview = contentPreview.value
    if (!preview) return
    if (event.type === 'MODEL_COMPLETED') {
      const modelCallId = typeof payload.modelCallId === 'string' ? payload.modelCallId : ''
      if (modelCallId === preview.modelCallId && payload.toolCallCount === 0) {
        // 纯文本轮：预览定格，最终 ASSISTANT 消息落库后由消息核对收口——回答只出现一次
        preview.finalized = true
      } else {
        // 带工具的轮次正文提交后成为既有过渡说明，预览立即让位
        contentPreview.value = null
      }
      return
    }
    if (event.type === 'RUN_SUCCEEDED') {
      if (!preview.finalized) contentPreview.value = null
      return
    }
    if (event.type === 'RUN_FAILED' || event.type === 'RUN_PAUSED' || event.type === 'RUN_CANCELED'
      || event.type === 'RUN_BUDGET_EXCEEDED' || event.type === 'WAITING_FOR_USER_INPUT'
      || event.type === 'RUN_RESUMED' || event.type === 'RUN_RETRY_SCHEDULED') {
      // 失败/暂停/恢复/等待输入不保留未提交正文，避免旧文本混入新请求或冒充成功答案
      contentPreview.value = null
    }
  }
  function applyContentFrame(frame: AgentContentFrame, isCurrent: () => boolean) {
    if (!isCurrent()) return
    const preview = contentPreview.value
    if (!preview || frame.modelCallId !== preview.modelCallId) return
    // 请求已结束（MODEL_COMPLETED 已提交）：保留已定格文字等待最终消息替换，
    // 只接受同一请求的 final 快照，不再接收迟到的新增正文
    if (preview.finalized && !frame.final) return
    if (frame.revision <= preview.revision) return
    if (frame.text.length >= CONTENT_PREVIEW_MAX_CHARS) {
      preview.text = frame.text.slice(0, CONTENT_PREVIEW_MAX_CHARS)
      preview.truncated = true
    } else {
      preview.text = frame.text
      preview.truncated = false
    }
    preview.revision = frame.revision
    if (frame.final) preview.finalized = true
  }
  // 最终回答落库后立即收口预览：消息列表出现本运行的 ASSISTANT 消息即丢弃预览
  watch(messages, (list) => {
    const preview = contentPreview.value
    if (!preview) return
    if (list.some(m => m.role === 'ASSISTANT' && (m.runId ?? null) === preview.runId)) {
      contentPreview.value = null
    }
  })
  const activeRunState = computed(() => activeRun.value ? agentRunPresentation(activeRun.value) : null)
  const runTone = computed(() => {
    const severity = activeRunState.value?.severity
    return severity === 'error' ? 'danger' : (severity ?? 'info')
  })
  const removedContextKeys = ref(new Set<keyof AgentPageContext>())
  let timer: number | undefined
  let streamController: AbortController | undefined
  // 组件卸载后到达的响应不得再触碰任何页面状态或重启事件订阅
  let disposed = false

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
    const requestedProjectId = projectId.value
    const requestedSessionId = sessionId.value
    const requestedToken = restoreSeq
    const loaded = requestedSessionId
      ? (await agentApi.messages(requestedProjectId, requestedSessionId)).data : []
    // 消息加载返回时页面可能已切换或被重新恢复（A→B→A 后项目/会话相同、恢复代次已变）：
    // 迟到的消息加载不得覆盖当前视图
    if (disposed || projectId.value !== requestedProjectId
      || sessionId.value !== requestedSessionId || restoreSeq !== requestedToken) return
    messages.value = loaded
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
    runDetail.value = null
    approvals.value = []
    timeline.value = emptyAgentTimeline()
    contentPreview.value = null
    const [msgs, latest] = await Promise.all([
      agentApi.messages(pid, id),
      agentApi.latestRun(pid, id),
    ])
    if (!fresh()) return
    messages.value = msgs.data
    runDetail.value = latest.data
    const run = latest.data?.run
    if (!run) return
    if (latest.data?.pauseRequestedAt) run.pauseRequestedAt = latest.data.pauseRequestedAt
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
        timeline.value = emptyAgentTimeline()
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
    // 从暂停/正在暂停的界面发出：显式绑定当前 runId，由后端统一入口识别续跑意图
    const scopedRun = activeRun.value
    const pausedRunId = scopedRun && (scopedRun.status === 'PAUSED' || pausePending.value)
      ? scopedRun.id
      : null
    // 发送时捕获页面作用域：响应（含其后续消息加载）只属于发起时的页面。
    // 切项目/会话、页面被其他恢复重建（restoreSeq 变化）、当前运行已换或组件卸载后，
    // 迟到的成功/失败响应都不得修改新页面状态、清草稿、换订阅或显示旧请求错误。
    const requestedProjectId = projectId.value
    const requestedSessionId = sessionId.value
    // 期望代次：本响应自己重建视图（新任务分支递增 restoreSeq）后随之更新，
    // 不永久豁免代次核对——A→B→A 后 ID 相同但代次已变，旧副作用必须失效
    let expectedToken = restoreSeq
    const sentContent = content
    const stale = () => disposed
      || projectId.value !== requestedProjectId || sessionId.value !== requestedSessionId
      || restoreSeq !== expectedToken
      || (pausedRunId ? activeRun.value?.id !== pausedRunId : false)
    sending.value = true
    try {
      const run = (await agentApi.submit(projectId.value, sessionId.value, {
        content,
        skillCode: selectedSkillCode.value,
        pageContext: currentPageContext(),
        pausedRunId,
      })).data
      if (stale()) return
      if (pausedRunId && run.id === pausedRunId) {
        // 同一个运行被恢复：不重建时间线、不追加新任务消息。事件流已推进
        // （RUN_RESUMED 及之后已应用）时不回退状态、不重启订阅；发送后新增的
        // 草稿不属于本次输入，不清空。
        const current = timeline.value.run
        const alreadyAdvanced = Boolean(current && current.id === run.id && current.status !== 'PAUSED')
        if (question.value === sentContent) {
          question.value = ''
          selectedSkillCode.value = null
        }
        if (!alreadyAdvanced) {
          if (current && current.id === run.id) {
            timeline.value.run = { ...current, status: run.status, pauseRequestedAt: null }
          }
          resumeEventStream()
        }
        await loadMessages()
        return
      }
      ++restoreSeq
      expectedToken = restoreSeq
      runDetail.value = null
      timeline.value = emptyAgentTimeline(run)
      if (question.value === sentContent) {
        question.value = ''
        selectedSkillCode.value = null
      }
      await loadMessages()
      // 消息加载期间页面可能已切换或被重新恢复（含 A→B→A 后 ID 相同）：
      // 不把本次运行的订阅强加给代次已变化的页面
      if (stale()) return
      startEventStream(run)
    } catch (reason) {
      if (!stale()) fail(reason, 'Agent 消息发送')
    } finally { sending.value = false }
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
    contentPreview.value = null
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
          // 只有本次被时间线接受的持久事件才推进预览生命周期：
          // 旧序号/重复序号/其他运行的事件由同一套接受判断拒绝，预览不得被其回退
          if (applyAgentEvent(timeline.value, event)) applyPreviewEvent(event)
        },
        frame => applyContentFrame(frame, fresh),
      )
      if (!fresh()) return
      timeline.value.connected = false
      if (!controller.signal.aborted) {
        const persisted = (await agentApi.run(projectId.value, runId)).data
        if (!fresh()) return
        runDetail.value = persisted
        reconcileAgentRun(timeline.value, persisted.run)
      }
      if (controller.signal.aborted || timeline.value.run?.status === 'SUCCEEDED'
        || timeline.value.run?.status === 'FAILED' || timeline.value.run?.status === 'CANCELED'
        || timeline.value.run?.status === 'BUDGET_EXCEEDED'
        || timeline.value.run?.status === 'WAITING_FOR_APPROVAL'
        || timeline.value.run?.status === 'WAITING_FOR_USER_INPUT'
        || timeline.value.run?.status === 'PAUSED') {
        await Promise.all([
          loadMessages(),
          refreshApprovals({ projectId: projectId.value, sessionId: sessionId.value, runId }),
        ])
        // 终态收口：消息核对未清掉的预览（如无 ASSISTANT 消息的失败轮）不留到页面静止态
        if (fresh()) contentPreview.value = null
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
      ElMessage.success('已请求结束本次运行')
    } catch (reason) { fail(reason, 'Agent 取消') }
  }
  /**
   * 请求暂停当前运行。响应按作用域核对：切项目/会话、当前 run 已换或页面被其他
   * 恢复重建后，迟到响应不覆盖视图、不停止不属于当前作用域的订阅。
   */
  const pauseBusy = ref(false)
  async function pauseActiveRun() {
    const run = activeRun.value
    if (!run || pauseBusy.value || sending.value || !canPause.value) return
    const requestedProjectId = projectId.value
    const requestedSessionId = sessionId.value
    const requestedRunId = run.id
    const requestedToken = restoreSeq
    pauseBusy.value = true
    try {
      const detail = (await agentApi.pause(requestedProjectId, requestedRunId)).data
      if (projectId.value !== requestedProjectId || sessionId.value !== requestedSessionId
        || restoreSeq !== requestedToken || activeRun.value?.id !== requestedRunId) {
        return
      }
      // 作用域正确 ≠ 快照仍然最新：回包快照落后于已应用到时间线的事件
      // （如 RUN_SUCCEEDED 已先行到达）时，旧快照不得覆盖状态、暂停标记或订阅决定。
      // 暂停/恢复/完成的状态迁移都伴随事件序号，序号核对覆盖所有非终态之间的顺序。
      if ((detail.lastEventSequence ?? 0) < timeline.value.lastSequence) return
      if (detail.run) {
        timeline.value.run = { ...detail.run, pauseRequestedAt: detail.pauseRequestedAt ?? null }
        if (detail.run.status === 'PAUSED') {
          // 已确认暂停：停止执行流的无意义重连；SSE 不活跃是暂停期的正常状态
          streamController?.abort()
          streamController = undefined
          timeline.value.connected = false
          await loadMessages()
        }
      }
    } catch (reason) { fail(reason, 'Agent 暂停') } finally { pauseBusy.value = false }
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
  onUnmounted(() => {
    disposed = true
    window.clearTimeout(timer)
    streamController?.abort()
  })

  return {
    projectId, currentUserId, mobileView, showInspector, runTone,
    pendingApprovals, resolvedApprovals, sessions, summaries, sessionId, messages,
    approvals, runDetail, activities, conversationBlocks, activeModel,
    summaryOf, isCreator, relativeTime, runStatusLabel, evidenceTitle, evidenceDetail,
    statusDot, members, skills, selectedSkillCode, question, busy, sending,
    activeRun, timeline, activeRunState, pageContext, hasPageContext, contentPreview,
    load, newSession, renameSession, deleteSession, send, removeContext, clearContext,
    approve, reject, approvalBusy, continueRunHandler, retryActiveRun, cancelActiveRun,
    pauseActiveRun, pauseBusy, pausePending, canPause, time,
  }
}
