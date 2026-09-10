<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { showApiError } from '../../api/api-result'
import PageHeader from '../../shared/PageHeader.vue'
import EmptyState from '../../shared/EmptyState.vue'
import { MoreFilled } from '@element-plus/icons-vue'
import AgentContextChips from './AgentContextChips.vue'
import AgentRunTimeline from './AgentRunTimeline.vue'
import AgentApprovalCard from './AgentApprovalCard.vue'
import SemanticDiff from '../../shared/SemanticDiff.vue'
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

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const projectId = computed(() => String(route.params.projectId ?? ''))
const currentUserId = computed(() => String(auth.currentUser?.id ?? ''))
const mobileView = ref<'sessions' | 'chat' | 'inspector'>('chat')
const showInspector = ref(true)
const runTone = computed(() => {
  const severity = activeRunState.value?.severity
  return severity === 'error' ? 'danger' : (severity ?? 'info')
})
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
const summaryOf = (id: string) => summaries.value.find((s) => s.id === id)
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
  activeRun.value = timeline.value.run
  if (!fresh()) return
  await refreshApprovals()
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
    activeRun.value = run
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
  try {
    timeline.value.connected = true
    await streamAgentEvents(
      `/api/v1/projects/${projectId.value}/agent/runs/${runId}/events`,
      timeline.value.lastSequence,
      controller.signal,
      event => {
        applyAgentEvent(timeline.value, event)
        activeRun.value = timeline.value.run
      },
    )
    timeline.value.connected = false
    if (!controller.signal.aborted) {
      const persisted = (await agentApi.run(projectId.value, runId)).data.run
      reconcileAgentRun(timeline.value, persisted)
      activeRun.value = timeline.value.run
    }
    if (controller.signal.aborted || timeline.value.run?.status === 'SUCCEEDED'
      || timeline.value.run?.status === 'FAILED' || timeline.value.run?.status === 'CANCELED'
      || timeline.value.run?.status === 'BUDGET_EXCEEDED'
      || timeline.value.run?.status === 'WAITING_FOR_APPROVAL'
      || timeline.value.run?.status === 'WAITING_FOR_USER_INPUT') {
      await Promise.all([loadMessages(), refreshApprovals()])
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
async function refreshApprovals() {
  if (!activeRun.value) { approvals.value = []; return }
  approvals.value = (await agentApi.runApprovals(projectId.value, activeRun.value.id)).data
}
async function approve(item: AgentApproval) {
  try {
    await ElMessageBox.confirm('确认按此差异写入项目数据？操作将记录审批人和结果。', '批准 Agent 提案', { type: 'warning' })
  } catch { return }
  try {
    await agentApi.approve(projectId.value, item)
    await restoreSession(sessionId.value)
    ElMessage.success('已批准并执行')
  }
  catch (reason) { fail(reason, 'Agent 提案批准') }
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
  sending.value = true
  try {
    await agentApi.retry(projectId.value, activeRun.value.id)
    await restoreSession(sessionId.value)
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
  let reason_text: string
  try {
    const result = await ElMessageBox.prompt('请输入拒绝原因', '拒绝 Agent 提案', { inputValidator: value => Boolean(value.trim()) })
    reason_text = result.value
  } catch { return }
  try {
    await agentApi.reject(projectId.value, item, reason_text)
    await refreshApprovals()
  }
  catch (reason) { fail(reason, 'Agent 提案拒绝') }
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
</script>

<template>
  <section class="workspace-page agent-page" v-loading="busy">
    <PageHeader title="项目协作 Agent" eyebrow="AI 工作区">
      <template #actions>
        <el-tag v-if="activeRunState" :type="runTone" effect="light">{{ activeRunState.title }}</el-tag>
        <el-button class="inspector-toggle" @click="showInspector = !showInspector">
          {{ showInspector ? '隐藏检查器' : '显示检查器' }}
        </el-button>
      </template>
    </PageHeader>
    <div class="mobile-switch">
      <el-segmented v-model="mobileView" :options="[{ label: '会话', value: 'sessions' }, { label: '对话', value: 'chat' }, { label: '检查器', value: 'inspector' }]" />
    </div>
    <div class="agent-layout agent-workspace" :class="{ 'hide-inspector': !showInspector }" :data-view="mobileView">
      <aside class="agent-rail agent-sessions" aria-label="会话历史" :data-view="mobileView">
            <el-button type="primary" plain @click="newSession">新建会话</el-button>
            <div v-for="item in sessions" :key="item.id" class="session-row">
              <button class="session" :class="{ active: item.id === sessionId }" @click="sessionId = item.id; mobileView = 'chat'">
                <span class="session-title">{{ item.title }}</span>
                <span class="session-meta">
                  <i class="dot" :class="statusDot(summaryOf(item.id)?.latestRunStatus)" />
                  <span v-if="summaryOf(item.id)?.latestRunStatus">{{ runStatusLabel(summaryOf(item.id)?.latestRunStatus ?? '') }}</span>
                  <span>{{ relativeTime(summaryOf(item.id)?.latestActivityAt ?? item.updatedAt) }}</span>
                </span>
              </button>
              <el-dropdown v-if="isCreator(item)" trigger="click" class="session-overflow" placement="bottom-end">
                <button class="overflow-trigger" type="button" aria-label="会话更多操作" title="更多操作" @click.stop>
                  <el-icon><MoreFilled /></el-icon>
                </button>
                <template #dropdown>
                  <el-dropdown-menu>
                    <el-dropdown-item data-test="rename-agent-session" @click="renameSession(item)">重命名</el-dropdown-item>
                    <el-dropdown-item data-test="delete-agent-session" divided class="danger-item" @click="deleteSession(item)">删除</el-dropdown-item>
                  </el-dropdown-menu>
                </template>
              </el-dropdown>
            </div>
            <EmptyState v-if="!sessions.length" compact title="还没有协作会话" description="创建第一个会话，开始与 Agent 协作">
              <el-button type="primary" @click="newSession">创建会话</el-button>
            </EmptyState>
          </aside>
          <main class="agent-main conversation" :data-view="mobileView">
            <div class="messages">
              <div v-if="!conversationBlocks.length" class="conversation-empty">
                <h3>从一个问题开始</h3>
                <p>我可以检查风险、整理进度，或生成下一步行动建议。</p>
                <div class="suggested-prompts">
                  <el-button plain size="small" @click="question = '检查本周风险，并给出来源'">检查本周风险</el-button>
                  <el-button plain size="small" @click="question = '整理项目进度'">整理项目进度</el-button>
                  <el-button plain size="small" @click="question = '生成下一步行动建议'">生成下一步行动建议</el-button>
                </div>
              </div>
              <template v-for="block in conversationBlocks" :key="block.kind === 'message' ? block.message.id : block.items.map((a) => a.key).join('|')">
              <article v-if="block.kind === 'message'" :class="block.message.role.toLowerCase()">
                <strong>{{ block.message.role === 'USER' ? '你' : '项目协作 Agent' }}</strong>
                <p>{{ block.message.content }}</p>
                <details v-if="block.message.citations?.length"><summary>证据来源（{{ block.message.citations.length }}）</summary>
                  <ul class="evidence-list">
                    <li v-for="(c, i) in block.message.citations" :key="i"><strong>{{ evidenceTitle(c, `来源 ${i + 1}`) }}</strong><p v-if="evidenceDetail(c)">{{ evidenceDetail(c) }}</p></li>
                  </ul>
                  <details class="tech-details"><summary>技术详情</summary><pre>{{ JSON.stringify(block.message.citations, null, 2) }}</pre></details>
                </details>
                <details v-if="block.message.inferences?.length"><summary>推断依据（{{ block.message.inferences.length }}）</summary>
                  <ul class="evidence-list">
                    <li v-for="(f, i) in block.message.inferences" :key="i"><strong>{{ evidenceTitle(f, `推断 ${i + 1}`) }}</strong><p v-if="evidenceDetail(f)">{{ evidenceDetail(f) }}</p></li>
                  </ul>
                  <details class="tech-details"><summary>技术详情</summary><pre>{{ JSON.stringify(block.message.inferences, null, 2) }}</pre></details>
                </details>
              </article>
              <div v-else class="activity-group" aria-label="执行过程">
                <div v-for="act in block.items" :key="act.key" class="activity" :class="[act.kind, act.status]">
                  <span class="activity-mark" />
                  <div class="activity-body">
                    <div class="activity-title">{{ act.title }}</div>
                    <div v-if="act.detail" class="activity-detail">{{ act.detail }}</div>
                    <div v-if="act.count != null || act.durationMs != null" class="activity-meta">
                      <span v-if="act.count != null">{{ act.count }} 条</span>
                      <span v-if="act.durationMs != null">{{ (act.durationMs / 1000).toFixed(1) }}s</span>
                    </div>
                  </div>
                </div>
              </div>
              </template>
            </div>
            <div v-if="activeRun?.status === 'WAITING_FOR_USER_INPUT'" class="waiting-for-input">
              <el-alert title="Agent 需要你的输入" type="info" :closable="false" show-icon />
            </div>
            <div class="composer">
              <details v-if="skills.length" class="capability">
                <summary>能力：{{ skills.find((s) => s.code === selectedSkillCode)?.displayName ?? '自动识别' }}</summary>
                <div class="capability-options">
                  <el-check-tag :checked="selectedSkillCode === null" @change="selectedSkillCode = null">自动识别</el-check-tag>
                  <el-check-tag v-for="skill in skills" :key="skill.code" :checked="selectedSkillCode === skill.code" @change="selectedSkillCode = selectedSkillCode === skill.code ? null : skill.code">{{ skill.displayName }}</el-check-tag>
                </div>
              </details>
            <el-input v-model="question" type="textarea" :rows="3" maxlength="4000" show-word-limit
              :placeholder="activeRun?.status === 'WAITING_FOR_USER_INPUT' ? '请输入你的回复...' : '例如：检查本周进度和高风险事项，并给出来源'"
              @keydown.ctrl.enter.prevent="activeRun?.status === 'WAITING_FOR_USER_INPUT' ? continueRunHandler() : send" />
            <div class="composer-actions">
              <el-button v-if="activeRun?.status === 'WAITING_FOR_USER_INPUT'"
                type="primary" :loading="sending" :disabled="!question.trim()"
                @click="continueRunHandler">
                回复 Agent（Ctrl+Enter）
              </el-button>
              <el-button v-else type="primary" :loading="sending" :disabled="!question.trim()" @click="send">发送（Ctrl+Enter）</el-button>
              <el-button v-if="activeRun && !activeRunState?.terminal && activeRun?.status !== 'WAITING_FOR_USER_INPUT'" type="danger" plain @click="cancelActiveRun">停止运行</el-button>
            </div>
            </div>
          </main>
      <aside v-if="showInspector" class="agent-inspector" aria-label="运行检查器" :data-view="mobileView">
        <div v-if="!activeRun" class="inspector-empty-state">
          <h2>运行详情</h2>
          <p>执行 Agent 后，这里会展示计划、工具活动和审批。</p>
        </div>
        <section v-if="activeRun" class="inspector-block">
          <h2>运行 · {{ runStatusLabel(activeRun.status) }}</h2>
          <el-alert
            v-if="activeRunState"
            :title="activeRunState.title"
            :type="activeRunState.severity === 'error' ? 'error' : activeRunState.severity"
            :closable="false"
            show-icon
          />
          <p class="inspector-meta">SSE {{ timeline.connected ? '已连接' : '未连接' }}</p>
          <div v-if="activeRunState?.canRetry" class="inspector-actions">
            <el-button
              data-test="agent-retry"
              size="small"
              :loading="sending"
              @click="retryActiveRun"
            >
              重试
            </el-button>
          </div>
        </section>
        <section v-if="activeRun" class="inspector-block">
          <h2>待审批（{{ pendingApprovals.length }}）</h2>
          <div v-if="!pendingApprovals.length" class="board-empty">暂无待审批提案</div>
          <AgentApprovalCard v-for="item in pendingApprovals" :key="item.id" :approval="item" :members="members" @approve="approve" @reject="reject" />
        </section>
        <section v-if="activeRun" class="inspector-block">
          <h2>Plan</h2>
          <AgentRunTimeline :plan="timeline.plan" :status="activeRun?.status" />
        </section>
        <section v-if="activeRun" class="inspector-block">
          <h2>Resources</h2>
          <p class="inspector-meta">Steps {{ activeRun.stepsUsed }}/{{ activeRun.maxSteps }} · Tools {{ activeRun.toolCallsUsed }}/{{ activeRun.maxToolCalls }} · Tokens {{ activeRun.inputTokensUsed }}+{{ activeRun.outputTokensUsed }}</p>
        </section>
        <section v-if="activeRun && activities.length" class="inspector-block">
          <h2>Tool activity</h2>
          <div v-for="act in activities" :key="act.key" class="activity mini" :class="[act.kind, act.status]"><span class="activity-mark" /><span>{{ act.title }}</span></div>
        </section>
        <section v-if="runDetail" class="inspector-block">
          <h2>Advanced</h2>
          <p class="inspector-meta">Run {{ runDetail.run.id }}</p>
          <p class="inspector-meta">Sequence {{ runDetail.lastEventSequence }} · SSE {{ timeline.connected ? '已连接' : '未连接' }}</p>
        </section>
        <section v-if="hasPageContext" class="inspector-block">
          <h2>页面上下文</h2>
          <AgentContextChips :context="pageContext" @remove="removeContext" @clear="clearContext" />
        </section>
        <section v-if="activeRun && resolvedApprovals.length" class="inspector-block">
          <h2>已处理提案（{{ resolvedApprovals.length }}）</h2>
          <AgentApprovalCard v-for="item in resolvedApprovals" :key="item.id" :approval="item" :members="members" @approve="approve" @reject="reject" />
        </section>
      </aside>
    </div>
  </section>
</template>

<style scoped>
.agent-page{display:flex;flex-direction:column;gap:12px;min-height:calc(100dvh - 116px);padding-top:0;padding-bottom:16px}
/* Three zones separated by borders, not three floating cards */
.agent-workspace{display:grid;grid-template-columns:232px minmax(0,1fr) 328px;align-items:stretch;background:var(--color-surface);border:1px solid var(--color-border);border-radius:var(--radius-card);overflow:hidden;min-height:calc(100dvh - 240px)}
.agent-sessions,.agent-inspector{background:var(--color-surface);padding:14px;display:flex;flex-direction:column;gap:8px;min-height:0;border:0;border-radius:0}
.agent-rail{border-right:1px solid var(--color-border)}
.agent-inspector{border-left:1px solid var(--color-border);background:var(--color-surface-raised)}
.agent-sessions{max-height:calc(100dvh - 240px);overflow-y:auto}
.conversation-empty{display:grid;gap:8px;justify-items:center;text-align:center;padding:48px 20px}
.conversation-empty h3{margin:0;font-size:16px}
.conversation-empty p{margin:0;color:var(--color-text-secondary);font-size:13px}
.suggested-prompts{display:flex;gap:8px;flex-wrap:wrap;justify-content:center}
.composer{display:grid;gap:8px;position:sticky;bottom:0;background:var(--color-surface);padding-top:8px;border-top:1px solid var(--color-border)}
.capability{font-size:13px;color:var(--color-text-secondary)}
.capability summary{cursor:pointer;list-style:none}
.capability-options{display:flex;gap:6px;flex-wrap:wrap;margin-top:8px}
.inspector-empty-state{display:grid;gap:6px;padding:24px 8px;text-align:center}
.inspector-empty-state h2{font-size:14px;margin:0}
.inspector-empty-state p{font-size:13px;color:var(--color-text-secondary);margin:0}
.session-row{position:relative;display:grid;grid-template-columns:minmax(0,1fr) auto;gap:2px;align-items:start;padding:4px;border-radius:10px}
.overflow-trigger{display:none;align-items:center;justify-content:center;width:28px;height:28px;margin-top:6px;border:0;border-radius:8px;background:transparent;color:var(--color-text-secondary);cursor:pointer;flex:none}
.overflow-trigger:hover{background:var(--el-fill-color)}
.session-row:hover .overflow-trigger,.session-row:focus-within .overflow-trigger,.session-row:has(.session.active) .overflow-trigger{display:inline-flex}
.danger-item{color:var(--color-danger)}
.session-row:hover{background:var(--el-fill-color)}
.session{width:100%;height:40px;min-height:40px;border:0;border-radius:8px;padding:0 10px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;text-align:left;background:transparent;cursor:pointer}
.session.active{background:var(--el-color-primary-light-8);color:var(--el-color-primary)}
.conversation{min-height:0;padding:18px;display:grid;grid-template-rows:minmax(0,1fr) auto auto;gap:10px;background:var(--color-surface);border:1px solid var(--color-border);border-radius:12px}
.agent-workspace .conversation{border:0;border-radius:0;background:var(--color-surface)}
.agent-main{min-width:0;min-height:0;display:flex;flex-direction:column}
.messages{min-height:0;overflow:auto;max-height:calc(100dvh - 420px)}
article{max-width:78%;margin:12px 0;padding:12px 14px;border-radius:10px;background:var(--el-fill-color-light);white-space:pre-wrap}
article.user{margin-left:auto;background:var(--el-color-primary-light-9)}
article p{margin:8px 0}
article small{margin-right:12px;color:var(--el-text-color-secondary)}
.empty{text-align:center;padding:80px;color:var(--el-text-color-secondary)}
.waiting-for-input{margin:12px 0}
.composer-actions{display:flex;gap:8px;flex-wrap:wrap}
.skill-selector{display:flex;align-items:center;gap:6px;flex-wrap:wrap;padding:4px 0}
.skill-label{color:var(--el-text-color-secondary);font-size:13px;white-space:nowrap}
.agent-inspector{max-height:calc(100dvh - 220px);overflow-y:auto}
.inspector-block{display:grid;gap:8px;padding-bottom:12px;border-bottom:1px solid var(--color-border)}
.inspector-block:last-child{border-bottom:0}
.inspector-block h2{font-size:13px;color:var(--color-text-secondary);margin:0}
.inspector-empty{color:var(--color-text-muted);font-size:13px;margin:0}
.inspector-meta{color:var(--color-text-muted);font-size:12px;margin:0}
.inspector-actions{display:flex;gap:8px}
.inspector-toggle{display:none}
.dot{display:inline-block;width:8px;height:8px;border-radius:50%;background:#d1d5db;margin-right:6px}
.dot.run{background:#2563eb;box-shadow:0 0 0 3px rgba(37,99,235,.15)}
.dot.wait{background:#d97706}
.dot.fail{background:#dc2626}
.dot.done{background:#16a34a}
.session-title{display:block;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.session-meta{display:flex;gap:8px;font-size:12px;color:var(--el-text-color-secondary)}
.activity-group{display:grid;gap:6px;margin:12px 0}
.activity{display:flex;gap:10px;align-items:flex-start;padding:8px 10px;border-radius:10px;background:var(--el-fill-color-lighter)}
.activity.read,.activity.search{padding:6px 10px}
.activity.proposal{background:rgba(37,99,235,.06);border:1px solid rgba(37,99,235,.18)}
.activity.approval{background:rgba(217,119,6,.08);border:1px solid rgba(217,119,6,.25)}
.activity.failure,.activity.failed{background:rgba(220,38,38,.06);border:1px solid rgba(220,38,38,.2)}
.activity-mark{width:8px;height:8px;border-radius:50%;background:#9ca3af;margin-top:5px;flex:none}
.activity.running .activity-mark{background:#2563eb;animation:pulse 1.2s infinite}
.activity.done .activity-mark,.activity.success .activity-mark{background:#16a34a}
.activity.failed .activity-mark,.activity.failure .activity-mark{background:#dc2626}
.activity.waiting .activity-mark,.activity.approval .activity-mark{background:#d97706}
.activity-title{font-size:13px;font-weight:600}
.activity-detail{font-size:12px;color:var(--el-text-color-secondary)}
.activity-meta{display:flex;gap:8px;font-size:12px;color:var(--el-text-color-secondary)}
.activity.mini{font-size:12px;padding:4px 8px}
@keyframes pulse{0%{opacity:1}50%{opacity:.35}100%{opacity:1}}
.mobile-switch{display:none;margin-bottom:8px}
@media(max-width:1280px) and (min-width:761px){.agent-workspace{grid-template-columns:220px minmax(0,1fr)}.agent-inspector{position:fixed;top:0;right:0;bottom:0;width:min(420px,92vw);z-index:60;background:var(--color-surface);border-left:1px solid var(--color-border);box-shadow:-12px 0 32px rgba(15,23,42,.12);padding:16px;overflow-y:auto;max-height:none}.agent-workspace.hide-inspector .agent-inspector{display:none}}
@media(max-width:760px){.mobile-switch{display:block}.agent-workspace{grid-template-columns:1fr}.agent-workspace[data-view="sessions"] .conversation,.agent-workspace[data-view="sessions"] .agent-inspector{display:none}.agent-workspace[data-view="chat"] .agent-sessions,.agent-workspace[data-view="chat"] .agent-inspector{display:none}.agent-workspace[data-view="inspector"] .agent-sessions,.agent-workspace[data-view="inspector"] .conversation{display:none}.agent-inspector{max-height:none}}
.inspector-toggle{display:inline-flex}
@media(max-width:760px){.agent-workspace,.agent-workspace.hide-inspector{grid-template-columns:1fr}.agent-sessions{max-height:180px}.messages{max-height:none}.inspector-toggle{display:none}}
</style>
