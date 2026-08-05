<script setup lang="ts">
import { computed, onUnmounted, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { showApiError } from '../../api/api-result'
import PageHeader from '../../shared/PageHeader.vue'
import AgentContextChips from './AgentContextChips.vue'
import AgentRunTimeline from './AgentRunTimeline.vue'
import AgentApprovalCard from './AgentApprovalCard.vue'
import { agentApi } from './agent-api'
import { streamAgentEvents } from './agent-event-stream'
import { applyAgentEvent, emptyAgentTimeline, reconcileAgentRun, type AgentTimelineState } from './agent-run-store'
import { agentRunPresentation } from './agent-run-state'
import type { AgentApproval, AgentMessage, AgentPageContext, AgentRun, AgentSchedule, AgentSession, McpBinding } from './types'

const route = useRoute()
const projectId = computed(() => String(route.params.projectId ?? ''))
const tab = ref('chat')
const sessions = ref<AgentSession[]>([])
const sessionId = ref('')
const messages = ref<AgentMessage[]>([])
const approvals = ref<AgentApproval[]>([])
const schedules = ref<AgentSchedule[]>([])
const mcpBindings = ref<McpBinding[]>([])
const mcpBindingForm = reactive({ connectionId: '', allowedTools: '', allowedResources: '' })
const question = ref('')
const busy = ref(false)
const sending = ref(false)
const activeRun = ref<AgentRun | null>(null)
const timeline = ref<AgentTimelineState>(emptyAgentTimeline())
const activeRunState = computed(() => activeRun.value ? agentRunPresentation(activeRun.value) : null)
const scheduleDialog = ref(false)
const removedContextKeys = ref(new Set<keyof AgentPageContext>())
const schedule = ref({
  name: '每周项目检查', goal: '检查项目进度、风险并生成带来源的周报草案',
  skillCode: 'WEEKLY_REPORT', frequency: 'WEEKLY', timeZone: 'Asia/Shanghai', localTime: '09:00:00', weeklyDay: 1,
})
let timer: number | undefined
let streamController: AbortController | undefined

function fail(reason: unknown, action: string): void {
  showApiError(reason, action)
}
async function load() {
  if (!projectId.value) return
  busy.value = true
  try {
    const [s, a, jobs, bindings] = await Promise.all([
      agentApi.sessions(projectId.value), agentApi.approvals(projectId.value),
      agentApi.schedules(projectId.value), agentApi.mcpBindings(projectId.value),
    ])
    sessions.value = s.data; approvals.value = a.data; schedules.value = jobs.data; mcpBindings.value = bindings.data
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
      `确认删除会话“${item.title}”及其全部消息吗？`,
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
      pageContext: currentPageContext(),
    })).data
    activeRun.value = run
    timeline.value = emptyAgentTimeline(run)
    question.value = ''
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
      || timeline.value.run?.status === 'BUDGET_EXCEEDED') {
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
function pollRun(runId: string) {
  window.clearTimeout(timer)
  timer = window.setTimeout(() => pollRunOnce(runId), 900)
}
async function pollRunOnce(runId: string) {
  try {
    const detail = (await agentApi.run(projectId.value, runId)).data
    activeRun.value = detail.run
    const state = agentRunPresentation(detail.run)
    if (state.terminal) {
      await Promise.all([loadMessages(), refreshApprovals()])
      return
    }
    pollRun(runId)
  } catch (reason) {
    fail(reason, 'Agent 运行状态加载')
  }
}
async function retryActiveRun() {
  if (!activeRun.value || sending.value) return
  sending.value = true
  try {
    activeRun.value = (await agentApi.retry(projectId.value, activeRun.value.id)).data
    startEventStream(activeRun.value)
  } catch (reason) {
    fail(reason, 'Agent 运行重试')
  } finally {
    sending.value = false
  }
}
async function cancelActiveRun() {
  if (!activeRun.value) return
  try {
    await agentApi.cancel(projectId.value, activeRun.value.id)
    ElMessage.info('正在停止 Agent 运行')
  } catch (reason) { fail(reason, 'Agent 运行取消') }
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
async function reject(item: AgentApproval) {
  const result = await ElMessageBox.prompt('请输入拒绝原因', '拒绝 Agent 提案', { inputValidator: value => Boolean(value.trim()) })
  try {
    await agentApi.reject(projectId.value, item, result.value)
    await refreshApprovals(); pollRun(item.runId)
  }
  catch (reason) { fail(reason, 'Agent 提案拒绝') }
}
async function createSchedule() {
  if (!sessionId.value) await newSession()
  try {
    await agentApi.createSchedule(projectId.value, {
      ...schedule.value, sessionId: sessionId.value,
      weeklyDay: schedule.value.frequency === 'WEEKLY' ? schedule.value.weeklyDay : null,
    })
    schedules.value = (await agentApi.schedules(projectId.value)).data
    scheduleDialog.value = false; ElMessage.success('定时运行已创建')
  } catch (reason) { fail(reason, 'Agent 定时运行创建') }
}
async function toggle(item: AgentSchedule) {
  try {
    await agentApi.setSchedule(projectId.value, item, !item.enabled)
    schedules.value = (await agentApi.schedules(projectId.value)).data
  } catch (reason) { fail(reason, item.enabled ? 'Agent 定时运行停用' : 'Agent 定时运行启用') }
}
const splitMcpAllowlist = (value: string) => [...new Set(value.split(',').map(item => item.trim()).filter(Boolean))]
async function saveMcpBinding() {
  if (!mcpBindingForm.connectionId.trim()) return
  try {
    await agentApi.bindMcp(projectId.value, mcpBindingForm.connectionId.trim(), {
      enabled: true,
      allowedTools: splitMcpAllowlist(mcpBindingForm.allowedTools),
      allowedResources: splitMcpAllowlist(mcpBindingForm.allowedResources),
      configuration: {},
      version: mcpBindings.value.find(item => item.connectionId === mcpBindingForm.connectionId.trim())?.version ?? 0,
    })
    mcpBindings.value = (await agentApi.mcpBindings(projectId.value)).data
    ElMessage.success('项目 MCP 白名单已保存')
  } catch (reason) { fail(reason, '项目 MCP 白名单保存') }
}
async function removeMcpBinding(item: McpBinding) {
  try {
    await agentApi.unbindMcp(projectId.value, item.connectionId)
    mcpBindings.value = mcpBindings.value.filter(binding => binding.connectionId !== item.connectionId)
  } catch (reason) { fail(reason, '项目 MCP 绑定删除') }
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
    <PageHeader title="项目协作 Agent" />
    <el-tabs v-model="tab">
      <el-tab-pane label="协作对话" name="chat">
        <div class="chat-layout">
          <aside>
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
              <el-alert
                v-if="activeRunState"
                class="run-state"
                :title="activeRunState.title"
                :type="activeRunState.severity"
                :closable="false"
                show-icon
              >
                <template v-if="activeRunState.canRetry" #default>
                  <el-button
                    data-test="agent-retry"
                    size="small"
                    :loading="sending"
                    @click="retryActiveRun"
                  >
                    重试
                  </el-button>
                </template>
              </el-alert>
              <AgentRunTimeline :plan="timeline.plan" :events="timeline.events" :status="activeRun?.status" />
            </div>
            <AgentContextChips :context="pageContext" @remove="removeContext" @clear="clearContext" />
            <el-input v-model="question" type="textarea" :rows="4" maxlength="4000" show-word-limit
              placeholder="例如：检查本周进度和高风险事项，并给出来源" @keydown.ctrl.enter.prevent="send" />
            <div class="composer-actions">
              <el-button type="primary" :loading="sending" :disabled="!question.trim()" @click="send">发送（Ctrl+Enter）</el-button>
              <el-button v-if="activeRun && !activeRunState?.terminal" type="danger" plain @click="cancelActiveRun">停止运行</el-button>
            </div>
          </main>
        </div>
      </el-tab-pane>
      <el-tab-pane :label="`待审批 (${approvals.filter(x => x.status === 'PENDING').length})`" name="approvals">
        <el-empty v-if="!approvals.length" description="暂无 Agent 写入提案" />
        <AgentApprovalCard v-for="item in approvals" :key="item.id" :approval="item" @approve="approve" @reject="reject" />
      </el-tab-pane>
      <el-tab-pane label="定时运行" name="schedules">
        <el-button type="primary" @click="scheduleDialog = true">新建定时运行</el-button>
        <el-table :data="schedules">
          <el-table-column prop="name" label="名称" /><el-table-column prop="frequency" label="频率" width="100" />
          <el-table-column label="下次运行"><template #default="{ row }">{{ time(row.nextFireAt) }}</template></el-table-column>
          <el-table-column label="状态" width="100"><template #default="{ row }">{{ row.enabled ? '已启用' : '已停用' }}</template></el-table-column>
          <el-table-column label="操作" width="100"><template #default="{ row }"><el-button link @click="toggle(row)">{{ row.enabled ? '停用' : '启用' }}</el-button></template></el-table-column>
        </el-table>
      </el-tab-pane>
      <el-tab-pane label="MCP 工具" name="mcp">
        <el-alert title="只有项目 OWNER 可以修改绑定；连接 ID 可从系统管理中心的 MCP 连接列表复制。" type="info" show-icon />
        <el-form class="mcp-binding-form" label-position="top">
          <el-form-item label="MCP Connection ID"><el-input v-model="mcpBindingForm.connectionId" placeholder="UUID" /></el-form-item>
          <el-form-item label="项目允许的工具（逗号分隔）"><el-input v-model="mcpBindingForm.allowedTools" placeholder="get_file_contents, search_code" /></el-form-item>
          <el-form-item label="项目允许的资源（逗号分隔）"><el-input v-model="mcpBindingForm.allowedResources" /></el-form-item>
          <el-button type="primary" @click="saveMcpBinding">保存项目白名单</el-button>
        </el-form>
        <el-table :data="mcpBindings" empty-text="项目尚未绑定 MCP 连接">
          <el-table-column prop="connectionName" label="连接" min-width="160" />
          <el-table-column prop="connectionCode" label="代码" min-width="140" />
          <el-table-column label="工具白名单" min-width="240"><template #default="{ row }">{{ row.allowedTools.join(', ') || '无' }}</template></el-table-column>
          <el-table-column label="状态" width="120"><template #default="{ row }">{{ row.enabled && row.connectionEnabled ? '可用' : '停用' }}</template></el-table-column>
          <el-table-column label="操作" width="100"><template #default="{ row }"><el-button text type="danger" @click="removeMcpBinding(row)">解除绑定</el-button></template></el-table-column>
        </el-table>
      </el-tab-pane>
    </el-tabs>
    <el-dialog v-model="scheduleDialog" title="新建定时运行" width="520px">
      <el-form label-position="top">
        <el-form-item label="名称"><el-input v-model="schedule.name" /></el-form-item>
        <el-form-item label="运行目标"><el-input v-model="schedule.goal" type="textarea" :rows="3" /></el-form-item>
        <el-form-item label="频率"><el-select v-model="schedule.frequency"><el-option label="每天" value="DAILY" /><el-option label="每周" value="WEEKLY" /></el-select></el-form-item>
        <el-form-item v-if="schedule.frequency === 'WEEKLY'" label="星期（1=周一）"><el-input-number v-model="schedule.weeklyDay" :min="1" :max="7" /></el-form-item>
        <el-form-item label="本地时间"><el-time-picker v-model="schedule.localTime" value-format="HH:mm:ss" /></el-form-item>
        <el-form-item label="时区"><el-input v-model="schedule.timeZone" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="scheduleDialog = false">取消</el-button><el-button type="primary" @click="createSchedule">创建</el-button></template>
    </el-dialog>
  </section>
</template>

<style scoped>
.agent-page{display:flex;flex-direction:column;gap:12px;height:calc(100dvh - 116px);overflow:hidden;padding-top:16px;padding-bottom:16px}.agent-page :deep(.el-tabs){display:flex;min-height:0;flex:1;flex-direction:column}.agent-page :deep(.el-tabs__content){min-height:0;flex:1;overflow:auto}.agent-page :deep(.el-tab-pane){height:100%}.chat-layout{display:grid;grid-template-columns:240px 1fr;height:100%;min-height:0;border:1px solid var(--el-border-color);border-radius:10px;overflow:hidden}
aside{padding:14px;background:var(--el-fill-color-light);display:flex;min-height:0;overflow-y:auto;flex-direction:column;gap:8px}.session-row{display:grid;grid-template-columns:minmax(0,1fr);gap:4px;padding:4px;border-radius:10px}.session-row:hover{background:var(--el-fill-color)}.session{width:100%;height:40px;min-height:40px;border:0;border-radius:8px;padding:0 10px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;text-align:left;background:transparent;cursor:pointer}.session.active{background:var(--el-color-primary-light-8);color:var(--el-color-primary)}.session-actions{display:flex;justify-content:flex-end;gap:2px}
.conversation{min-height:0;padding:18px;display:grid;grid-template-rows:minmax(0,1fr) auto auto;gap:10px}.messages{min-height:0;overflow:auto}article{max-width:78%;margin:12px 0;padding:12px 14px;border-radius:10px;background:var(--el-fill-color-light);white-space:pre-wrap}article.user{margin-left:auto;background:var(--el-color-primary-light-9)}article p{margin:8px 0}article small{margin-right:12px;color:var(--el-text-color-secondary)}.empty{text-align:center;padding:80px;color:var(--el-text-color-secondary)}
.run-state{margin:12px 0}.run-state :deep(.el-alert__content){display:flex;align-items:center;justify-content:space-between;gap:12px;width:100%}
.agent-timeline{display:grid;gap:6px;margin:12px 0;padding:0;list-style:none}.agent-timeline li{display:flex;gap:10px;padding:8px 10px;border-left:3px solid var(--el-color-primary);background:var(--el-fill-color-lighter);font-size:13px}.agent-timeline span{color:var(--el-text-color-secondary)}.composer-actions{display:flex;gap:8px;flex-wrap:wrap}
.approval{margin-bottom:12px}.approval :deep(.el-card__header){display:flex;justify-content:space-between}.approval-fields{display:grid;grid-template-columns:120px minmax(0,1fr);gap:8px 16px;margin:0 0 14px}.approval-fields dt{color:var(--el-text-color-secondary)}.approval-fields dd{margin:0;font-weight:600}.approval-expiry{color:var(--el-text-color-secondary);font-size:13px}@media(max-width:760px){.agent-page{height:auto;min-height:calc(100dvh - 116px);overflow:visible}.chat-layout{grid-template-columns:1fr;min-height:620px}aside{max-height:180px;overflow:auto}}
</style>
