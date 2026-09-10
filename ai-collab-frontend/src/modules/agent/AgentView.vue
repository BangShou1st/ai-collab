<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { showApiError } from '../../api/api-result'
import PageHeader from '../../shared/PageHeader.vue'
import AgentContextChips from './AgentContextChips.vue'
import AgentRunTimeline from './AgentRunTimeline.vue'
import AgentApprovalCard from './AgentApprovalCard.vue'
import { agentApi } from './agent-api'
import { projectApi } from '../project/project-api'
import type { ProjectMember } from '../project/types'
import { streamAgentEvents } from './agent-event-stream'
import { applyAgentEvent, emptyAgentTimeline, reconcileAgentRun, type AgentTimelineState } from './agent-run-store'
import { agentRunPresentation } from './agent-run-state'
import type { AgentApproval, AgentMessage, AgentPageContext, AgentRun, AgentSession, AgentSkill } from './types'

const route = useRoute()
const projectId = computed(() => String(route.params.projectId ?? ''))
const showInspector = ref(true)
const runTone = computed(() => {
  const severity = activeRunState.value?.severity
  return severity === 'error' ? 'danger' : (severity ?? 'info')
})
const pendingApprovals = computed(() => approvals.value.filter((x) => x.status === 'PENDING'))
const resolvedApprovals = computed(() => approvals.value.filter((x) => x.status !== 'PENDING'))
const sessions = ref<AgentSession[]>([])
const sessionId = ref('')
const messages = ref<AgentMessage[]>([])
const approvals = ref<AgentApproval[]>([])
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
    const [s, a, skillList, m] = await Promise.all([
      agentApi.sessions(projectId.value), agentApi.approvals(projectId.value),
      agentApi.skills(projectId.value), projectApi.listMembers(projectId.value),
    ])
    sessions.value = s.data; approvals.value = a.data; skills.value = skillList.data; members.value = m.data
    if (!sessionId.value && sessions.value[0]) sessionId.value = sessions.value[0].id
  } catch (reason) { fail(reason, 'Agent 工作区加载') } finally { busy.value = false }
}
async function loadMessages() {
  messages.value = sessionId.value
    ? (await agentApi.messages(projectId.value, sessionId.value)).data : []
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
function currentPageContext(): AgentPageContext {
  const id = (key: string) => {
    const value = route.params[key]
    return typeof value === 'string' && /^[0-9a-f-]{36}$/i.test(value) ? value : null
  }
  return {
    route: String(route.name ?? route.path).slice(0, 80),
    selectedTaskId: removedContextKeys.value.has('selectedTaskId') ? null : id('taskId'),
    selectedMilestoneId: removedContextKeys.value.has('selectedMilestoneId') ? null : id('milestoneId'),
    selectedDocumentId: removedContextKeys.value.has('selectedDocumentId') ? null : id('documentId'),
    selectedPlanId: removedContextKeys.value.has('selectedPlanId') ? null : id('planId'),
    filters: {},
  }
}
const pageContext = computed(currentPageContext)
function removeContext(key: keyof AgentPageContext) {
  removedContextKeys.value = new Set([...removedContextKeys.value, key])
}
function clearContext() {
  removedContextKeys.value = new Set(['selectedTaskId', 'selectedMilestoneId', 'selectedDocumentId', 'selectedPlanId'])
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
async function refreshApprovals() { approvals.value = (await agentApi.approvals(projectId.value)).data }
async function approve(item: AgentApproval) {
  await ElMessageBox.confirm('确认按此差异写入项目数据？操作将记录审批人和结果。', '批准 Agent 提案', { type: 'warning' })
  try {
    await agentApi.approve(projectId.value, item)
    await refreshApprovals()
    const detail = (await agentApi.run(projectId.value, item.runId)).data
    startEventStream(detail.run)
    ElMessage.success('已批准并执行')
  }
  catch (reason) { fail(reason, 'Agent 提案批准') }
}
async function continueRun(content: string) {
  if (!activeRun.value || activeRun.value.status !== 'WAITING_FOR_USER_INPUT') return
  sending.value = true
  try {
    const run = (await agentApi.continueRun(projectId.value, activeRun.value.id, content)).data
    activeRun.value = run
    timeline.value = emptyAgentTimeline(run)
    question.value = ''
    await loadMessages()
    startEventStream(run)
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
    const run = (await agentApi.retry(projectId.value, activeRun.value.id)).data
    activeRun.value = run
    timeline.value = emptyAgentTimeline(run)
    await loadMessages()
    startEventStream(run)
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
  const result = await ElMessageBox.prompt('请输入拒绝原因', '拒绝 Agent 提案', { inputValidator: value => Boolean(value.trim()) })
  try {
    await agentApi.reject(projectId.value, item, result.value)
    await refreshApprovals()
  }
  catch (reason) { fail(reason, 'Agent 提案拒绝') }
}
const time = (value: string) => new Date(value).toLocaleString('zh-CN')
watch(projectId, load, { immediate: true })
watch(sessionId, () => {
  streamController?.abort()
  activeRun.value = null
  loadMessages().catch(reason => fail(reason, 'Agent 消息加载'))
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
    <div class="agent-workspace" :class="{ 'hide-inspector': !showInspector }">
      <aside class="agent-sessions" aria-label="会话历史">
            <el-button type="primary" plain @click="newSession">新建会话</el-button>
            <div v-for="item in sessions" :key="item.id" class="session-row">
              <button class="session" :class="{ active: item.id === sessionId }"
                @click="sessionId = item.id">{{ item.title }}</button>
              <div class="session-actions">
                <el-button
                  data-test="rename-agent-session"
                  text
                  size="small"
                  @click="renameSession(item)"
                >
                  重命名
                </el-button>
                <el-button
                  data-test="delete-agent-session"
                  text
                  type="danger"
                  size="small"
                  @click="deleteSession(item)"
                >
                  删除
                </el-button>
              </div>
            </div>
            <el-empty v-if="!sessions.length" description="还没有协作会话">
              <el-button type="primary" @click="newSession">创建会话</el-button>
            </el-empty>
          </aside>
          <main class="conversation">
            <div class="messages">
              <div v-if="!messages.length" class="empty">开始一个新对话</div>
              <article v-for="message in messages" :key="message.id" :class="message.role.toLowerCase()">
                <strong>{{ message.role === 'USER' ? '你' : '项目协作 Agent' }}</strong>
                <p>{{ message.content }}</p>
                <small v-if="message.citations?.length">来源 {{ message.citations.length }} 条</small>
                <small v-if="message.inferences?.length">推断 {{ message.inferences.length }} 条</small>
              </article>
            </div>
            <div v-if="activeRun?.status === 'WAITING_FOR_USER_INPUT'" class="waiting-for-input">
              <el-alert title="Agent 需要你的输入" type="info" :closable="false" show-icon />
            </div>
            <div class="skill-selector" v-if="skills.length">
              <span class="skill-label">选择能力：</span>
              <el-check-tag
                v-for="skill in skills" :key="skill.code"
                :checked="selectedSkillCode === skill.code"
                @change="selectedSkillCode = selectedSkillCode === skill.code ? null : skill.code"
              >{{ skill.displayName }}</el-check-tag>
              <el-check-tag
                :checked="selectedSkillCode === null"
                @change="selectedSkillCode = null"
              >自动识别</el-check-tag>
            </div>
            <el-input v-model="question" type="textarea" :rows="4" maxlength="4000" show-word-limit
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
          </main>
      <aside v-if="showInspector" class="agent-inspector" aria-label="运行检查器">
        <section class="inspector-block">
          <h2>运行状态</h2>
          <el-alert
            v-if="activeRunState"
            :title="activeRunState.title"
            :type="activeRunState.severity === 'error' ? 'error' : activeRunState.severity"
            :closable="false"
            show-icon
          />
          <p v-else class="inspector-empty">暂无运行</p>
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
        <section class="inspector-block">
          <h2>待审批（{{ pendingApprovals.length }}）</h2>
          <el-empty v-if="!pendingApprovals.length" description="暂无待审批提案" :image-size="60" />
          <AgentApprovalCard v-for="item in pendingApprovals" :key="item.id" :approval="item" :members="members" @approve="approve" @reject="reject" />
        </section>
        <section class="inspector-block">
          <h2>运行时间线</h2>
          <AgentRunTimeline :plan="timeline.plan" :events="timeline.events" :status="activeRun?.status" />
        </section>
        <section class="inspector-block">
          <h2>页面上下文</h2>
          <AgentContextChips :context="pageContext" @remove="removeContext" @clear="clearContext" />
        </section>
        <section v-if="resolvedApprovals.length" class="inspector-block">
          <h2>已处理提案（{{ resolvedApprovals.length }}）</h2>
          <AgentApprovalCard v-for="item in resolvedApprovals" :key="item.id" :approval="item" :members="members" @approve="approve" @reject="reject" />
        </section>
      </aside>
    </div>
  </section>
</template>

<style scoped>
.agent-page{display:flex;flex-direction:column;gap:12px;min-height:calc(100dvh - 116px);padding-top:16px;padding-bottom:16px}
.agent-workspace{display:grid;grid-template-columns:248px minmax(0,1fr) 340px;gap:12px;align-items:start}
.agent-sessions,.agent-inspector{background:var(--color-surface);border:1px solid var(--color-border);border-radius:12px;padding:14px;display:flex;flex-direction:column;gap:8px;min-height:0}
.agent-sessions{max-height:calc(100dvh - 220px);overflow-y:auto}
.session-row{display:grid;grid-template-columns:minmax(0,1fr);gap:4px;padding:4px;border-radius:10px}
.session-row:hover{background:var(--el-fill-color)}
.session{width:100%;height:40px;min-height:40px;border:0;border-radius:8px;padding:0 10px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;text-align:left;background:transparent;cursor:pointer}
.session.active{background:var(--el-color-primary-light-8);color:var(--el-color-primary)}
.session-actions{display:flex;justify-content:flex-end;gap:2px}
.conversation{min-height:0;padding:18px;display:grid;grid-template-rows:minmax(0,1fr) auto auto;gap:10px;background:var(--color-surface);border:1px solid var(--color-border);border-radius:12px}
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
@media(max-width:1280px){.agent-workspace{grid-template-columns:220px minmax(0,1fr)}.agent-workspace.hide-inspector{grid-template-columns:220px minmax(0,1fr)}.agent-inspector{grid-column:1/-1;max-height:none}.inspector-toggle{display:inline-flex}}
@media(max-width:760px){.agent-workspace,.agent-workspace.hide-inspector{grid-template-columns:1fr}.agent-sessions{max-height:180px}.messages{max-height:none}}
</style>
