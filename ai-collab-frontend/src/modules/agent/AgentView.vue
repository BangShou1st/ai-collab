<script setup lang="ts">
import { MoreFilled } from '@element-plus/icons-vue'
import { computed, nextTick, ref, watch } from 'vue'
import DocumentBodyReader from '../document/DocumentBodyReader.vue'
import AgentPlanningCards from './AgentPlanningCards.vue'
import PageHeader from '../../shared/PageHeader.vue'
import EmptyState from '../../shared/EmptyState.vue'
import AgentContextChips from './AgentContextChips.vue'
import AgentRunTimeline from './AgentRunTimeline.vue'
import AgentApprovalCard from './AgentApprovalCard.vue'
import ActivitySummaryBar from './ActivitySummaryBar.vue'
import ActivityGroupList from './ActivityGroupList.vue'
import RunStatusCard from './RunStatusCard.vue'
import SemanticDiff from '../../shared/SemanticDiff.vue'
import { markdown } from '../knowledge/knowledge-render'
import { parseAgentProse, agentQuestionLines } from './agent-prose'
import { useAgentWorkspace } from './use-agent-workspace'

const {
  projectId, currentUserId, mobileView, showInspector, runTone,
  pendingApprovals, resolvedApprovals, sessions, summaries, sessionId, messages,
  approvals, runDetail, activities, conversationBlocks, activeModel, contentPreview,
  summaryOf, isCreator, relativeTime, runStatusLabel, evidenceTitle, evidenceDetail,
  statusDot, members, skills, selectedSkillCode, question, busy, sending,
  activeRun, timeline, activeRunState, pageContext, hasPageContext,
  sseDiagnostics, describeAgentSseDisconnect,
  newSession, renameSession, deleteSession, send, removeContext, clearContext,
  approve, reject, approvalBusy, continueRunHandler, retryActiveRun, cancelActiveRun,
  pauseActiveRun, pauseBusy, pausePending, canPause, time,
} = useAgentWorkspace()
const source = ref<{ documentId: string; chunkId: string } | null>(null)
// 会话栏收起状态：视图层本地状态，不进入运行状态投影
const showSessions = ref(true)
function sourceIdentity(c: unknown): { documentId: string; chunkId: string } | null {
  if (!c || typeof c !== 'object') return null
  const value = c as Record<string, unknown>
  return typeof value.documentId === 'string' && typeof value.chunkId === 'string' ? { documentId: value.documentId, chunkId: value.chunkId } : null
}

// 最终回答用 Markdown 渲染（marked + DOMPurify 严格净化，禁外链/图片/脚本）；
// 同一展示 helper 解析协议区域：正文去掉控制标记，追问区域独立返回、单独渲染（不并入正文编号列表）
const answerHtmlById = computed<Record<string, string>>(() => {
  const out: Record<string, string> = {}
  for (const m of messages.value) {
    if (m.role === 'ASSISTANT') {
      const parsed = parseAgentProse(m.content)
      out[m.id] = markdown(parsed.questions == null ? parsed.text : parsed.text + (parsed.text ? '\n\n' : '') + '（待确认事项见下方）')
    }
  }
  return out
})
// 追问区域按消息独立成块：问题行 + 从 1 开始的选项列表（R11：与正文步骤分开编号）
const questionLinesById = computed<Record<string, string[]>>(() => {
  const out: Record<string, string[]> = {}
  for (const m of messages.value) if (m.role === 'ASSISTANT') out[m.id] = agentQuestionLines(m.content)
  return out
})
// 临时预览同样只展示可读正文：控制标记前缀（含未判明的短前缀）不出现在页面上。
// 预览走与最终回答同一条 marked + DOMPurify 渲染管线：流式过程中 Markdown 即时成型，
// 避免先闪出 #/表格/加粗源码再跳变为排好版的结果；渲染失败回退为纯文本插值
const previewParsed = computed(() => parseAgentProse(contentPreview.value?.text ?? ''))
const previewProse = computed(() => {
  const parsed = previewParsed.value
  return parsed.questions == null ? parsed.text : parsed.text + (parsed.text ? '\n\n' : '') + parsed.questions
})
const previewHtml = computed(() => {
  if (!previewProse.value) return ''
  try {
    return markdown(previewProse.value)
  } catch {
    return ''
  }
})

/**
 * 用量行：新策略（累计 token 只统计）下不展示"剩余额度/百分比"，只展示实际用量与估算来源；
 * 旧运行（累计上限非 null）保留 x/y 形式，但仍不推断百分比。
 * 父运行的步骤/工具只展示**自身**计数——不把全树消耗与父自身额度比较。
 */
const tokenUsageLine = computed(() => {
  const run = activeRun.value
  if (!run) return ''
  const estimated = (run as { tokenUsageEstimated?: boolean }).tokenUsageEstimated
  const basis = estimated ? '（含估算）' : ''
  const inputPart = run.maxInputTokens == null
    ? `输入 ${run.inputTokensUsed}${basis}（累计只统计）`
    : `输入 ${run.inputTokensUsed}/${run.maxInputTokens}${basis}`
  const outputPart = run.maxOutputTokens == null
    ? `输出 ${run.outputTokensUsed}${basis}（累计只统计）`
    : `输出 ${run.outputTokensUsed}/${run.maxOutputTokens}${basis}`
  const selfScope = run.contextPolicyVersion === 2 ? ' · 本运行自身执行额度（不含子运行）' : ''
  return `Tokens ${inputPart} · ${outputPart}${selfScope} · Run ${runDetail.value?.run.id ?? run.id}`
})
/**
 * SSE 连接状态说明：区分「已连接 / 重连中 / 传输已重连（等待事件续接）/ 已恢复」。
 * "已恢复"只在持久事件或运行状态对齐后才显示——仅收到 HTTP 200 只算"传输已重连"，
 * 避免把传输重建误报成完整事件恢复；证据不足的情形不在此给出确定性归因。
 */
const sseStatusLine = computed(() => {
  const d = sseDiagnostics.value
  const detail = d.category
    ? `${describeAgentSseDisconnect(d.category)} · 断线前 seq ${d.lastSequence}`
    : ''
  if (d.state === 'RECONNECTING') return `重连中（${detail} · 第 ${d.attempts} 次尝试）`
  if (d.state === 'TRANSPORT_RECONNECTED') return `传输已重连 · 等待事件续接（${detail}）`
  if (d.state === 'RECOVERED') return `已恢复（${detail}）`
  return timeline.value.connected ? '已连接' : '未连接'
})
const currentSessionTitle = computed(() => sessions.value.find((item) => item.id === sessionId.value)?.title ?? '')

/** 主区轻量状态行：只放摘要条覆盖不到、或审批入口在右栏可能被收起的状态；
 *  失败详情、重试与诊断仍以右栏为准，这里不重复技术信息 */
const mainStateLine = computed(() => {
  const run = activeRun.value
  if (!run) return null
  switch (run.status) {
    case 'QUEUED': return '已排队，等待 Agent 处理'
    case 'RUNNING': return activities.value.length ? null : '正在处理…'
    case 'FAILED_RETRYABLE':
    case 'FAILED':
    case 'BUDGET_EXCEEDED': return activeRunState.value?.title ?? null
    case 'CANCELED': return '本次运行已取消'
    case 'WAITING_FOR_APPROVAL': return showInspector.value ? '提案等待批准，可在右侧审批' : '提案等待批准，打开「详情」处理'
    default: return null
  }
})
const mainStateTone = computed(() => {
  const severity = activeRunState.value?.severity
  return severity === 'error' ? 'error' : severity === 'warning' ? 'warning' : 'info'
})

/** SSE 异常态轻提示：断线重连在主区只给一行，诊断细节仍留在右栏 */
const ssePill = computed(() => {
  const state = sseDiagnostics.value.state
  if (state === 'RECONNECTING') return '连接中断，重连中'
  if (state === 'TRANSPORT_RECONNECTED') return '传输已重连，等待事件续接'
  return null
})

// 滚动跟随：用户停在底部时自动跟随新内容；向上翻看历史时不打断，只提示新内容
const messagesEl = ref<HTMLElement | null>(null)
const pinnedToBottom = ref(true)
const hasNewContent = ref(false)
function onMessagesScroll() {
  const el = messagesEl.value
  if (!el) return
  pinnedToBottom.value = el.scrollHeight - el.scrollTop - el.clientHeight < 80
  if (pinnedToBottom.value) hasNewContent.value = false
}
async function scrollToBottom() {
  await nextTick()
  const el = messagesEl.value
  if (el) el.scrollTop = el.scrollHeight
  hasNewContent.value = false
  pinnedToBottom.value = true
}
watch(conversationBlocks, () => {
  if (pinnedToBottom.value) void scrollToBottom()
  else hasNewContent.value = true
})
// 流式正文预览与对话块同等对待：底部跟随新内容，向上阅读不打断
watch(contentPreview, () => {
  if (!contentPreview.value?.text) return
  if (pinnedToBottom.value) void scrollToBottom()
  else hasNewContent.value = true
}, { deep: true })
watch(sessionId, () => {
  pinnedToBottom.value = true
  hasNewContent.value = false
  void scrollToBottom()
})
</script>

<template>
  <section class="workspace-page agent-page" v-loading="busy">
    <PageHeader title="项目协作 Agent" eyebrow="AI 工作区">
      <template #actions>
        <el-tag v-if="activeRunState" :type="runTone" effect="light">{{ activeRunState.title }}</el-tag>
        <el-button class="rail-toggle" data-test="agent-sessions-toggle" @click="showSessions = !showSessions">
          {{ showSessions ? '收起会话' : '会话' }}
        </el-button>
        <el-button class="inspector-toggle" data-test="agent-inspector-toggle" @click="showInspector = !showInspector">
          {{ showInspector ? '隐藏详情' : '详情' }}
        </el-button>
      </template>
    </PageHeader>
    <div class="mobile-switch">
      <el-segmented v-model="mobileView" :options="[{ label: '会话', value: 'sessions' }, { label: '对话', value: 'chat' }, { label: '详情', value: 'inspector' }]" />
    </div>
    <div class="agent-layout agent-workspace" :class="{ 'hide-inspector': !showInspector, 'hide-sessions': !showSessions }" :data-view="mobileView">
      <aside v-if="showSessions" class="agent-rail agent-sessions" aria-label="会话历史" :data-view="mobileView">
            <el-button type="primary" plain class="new-session" @click="newSession">新建会话</el-button>
            <div v-for="item in sessions" :key="item.id" class="session-row" :class="{ active: item.id === sessionId }">
              <button class="session" :class="{ active: item.id === sessionId }" :title="item.title" @click="sessionId = item.id; mobileView = 'chat'">
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
            <div class="conversation-head">
              <span v-if="currentSessionTitle" class="conversation-title">{{ currentSessionTitle }}</span>
              <span v-if="ssePill" class="sse-pill" data-test="agent-sse-status">{{ ssePill }}</span>
              <span v-if="activeModel" class="model-chip" :class="{ live: activeRun?.status === 'RUNNING' }" :title="activeModel.provider || activeModel.model">
                <span class="model-dot" aria-hidden="true" />{{ activeModel.model }}
              </span>
              <span v-else-if="activeRun" class="model-chip">模型未读取</span>
            </div>
            <div v-if="mainStateLine" class="run-state-line" :class="mainStateTone" data-test="agent-main-state">
              <i class="dot" :class="statusDot(activeRun?.status)" aria-hidden="true" />{{ mainStateLine }}
              <el-button v-if="activeRun && activeRunState?.canRetry" size="small" text type="primary" class="state-retry" @click="retryActiveRun">重试</el-button>
            </div>
            <div class="messages-wrap">
              <div ref="messagesEl" class="messages" @scroll="onMessagesScroll">
                <div v-if="!conversationBlocks.length" class="conversation-empty">
                  <h3>从一个问题开始</h3>
                  <p>我可以检查风险、整理进度，或生成下一步行动建议。</p>
                  <div class="suggested-prompts">
                    <el-button plain size="small" @click="question = '检查本周风险，并给出来源'">检查本周风险</el-button>
                    <el-button plain size="small" @click="question = '整理项目进度'">整理项目进度</el-button>
                    <el-button plain size="small" @click="question = '生成下一步行动建议'">生成下一步行动建议</el-button>
                  </div>
                </div>
                <template v-for="block in conversationBlocks" :key="block.kind === 'message' ? block.message.id : `activities-${activeRun?.id ?? 'none'}`">
                <article v-if="block.kind === 'message'" :class="['msg', block.message.role.toLowerCase()]">
                  <div v-if="block.message.role === 'ASSISTANT'" class="answer-body" v-html="answerHtmlById[block.message.id]" />
                  <div v-if="block.message.role === 'ASSISTANT' && (questionLinesById[block.message.id]?.length ?? 0) > 0" class="question-block" data-test="agent-question-block">
                    <p class="question-label">需要你的确认</p>
                    <ol class="question-options">
                      <li v-for="(line, i) in questionLinesById[block.message.id]" :key="i">{{ line.replace(/^\d+[.、]\s*/, '') }}</li>
                    </ol>
                  </div>
                  <p v-if="block.message.role !== 'ASSISTANT'" class="user-text">{{ block.message.content }}</p>
                  <details v-if="block.message.citations?.length" class="evidence">
                    <summary>来源（{{ block.message.citations.length }}）</summary>
                    <ul class="evidence-list">
                      <li v-for="(c, i) in block.message.citations" :key="i"><strong>{{ evidenceTitle(c, `来源 ${i + 1}`) }}</strong><p v-if="evidenceDetail(c)">{{ evidenceDetail(c) }}</p><el-button v-if="sourceIdentity(c)" text @click="source = sourceIdentity(c)">查看原文片段</el-button></li>
                    </ul>
                    <details class="tech-details"><summary>技术详情</summary><pre>{{ JSON.stringify(block.message.citations, null, 2) }}</pre></details>
                  </details>
                  <details v-if="block.message.inferences?.length" class="evidence">
                    <summary>推断依据（{{ block.message.inferences.length }}）</summary>
                    <ul class="evidence-list">
                      <li v-for="(f, i) in block.message.inferences" :key="i"><strong>{{ evidenceTitle(f, `推断 ${i + 1}`) }}</strong><p v-if="evidenceDetail(f)">{{ evidenceDetail(f) }}</p></li>
                    </ul>
                    <details class="tech-details"><summary>技术详情</summary><pre>{{ JSON.stringify(block.message.inferences, null, 2) }}</pre></details>
                  </details>
                </article>
                <div v-else class="activity-group" aria-label="执行过程">
                  <ActivitySummaryBar :items="block.items" :run-status="activeRun?.status ?? null" />
                  <details class="activity-detail-toggle">
                    <summary>
                      <span class="detail-toggle-label">执行详情</span>
                      <span class="activity-meta">{{ block.items.length }} 项事件</span>
                    </summary>
                    <div class="group-items">
                      <ActivityGroupList :items="block.items" />
                    </div>
                  </details>
                </div>
                </template>
                <!-- 临时正文预览：当前请求的实时输出，与最终回答同一渲染管线（marked+DOMPurify）；
                     最终回答落库后由持久消息收口，回答只出现一次 -->
                <div v-if="previewProse" class="streaming-preview answer-body" data-test="agent-content-preview">
                  <div v-if="previewHtml" class="preview-html" v-html="previewHtml" />
                  <p v-else>{{ previewProse }}<span v-if="!contentPreview?.finalized" class="preview-cursor" aria-hidden="true">▍</span></p>
                  <span v-if="contentPreview?.truncated" class="preview-note">内容较长，已停止预览追加，后台仍在继续生成</span>
                </div>
              </div>
              <button v-if="hasNewContent" class="new-content-pill" type="button" @click="scrollToBottom">查看新内容 ↓</button>
            </div>
            <div v-if="activeRun?.status === 'WAITING_FOR_USER_INPUT'" class="waiting-for-input">
              <el-alert title="Agent 需要你的输入" type="info" :closable="false" show-icon />
            </div>
            <div v-if="pausePending" class="waiting-for-input" data-test="agent-pausing-banner">
              <el-alert title="正在暂停，当前步骤完成后保留进度" type="warning" :closable="false" show-icon />
            </div>
            <div v-else-if="activeRun?.status === 'PAUSED'" class="waiting-for-input" data-test="agent-paused-banner">
              <el-alert title="已暂停，进度已保留。输入“继续”，接着完成当前任务" type="warning" :closable="false" show-icon />
            </div>
            <div class="composer">
              <details v-if="skills.length" class="capability">
                <summary>场景：{{ skills.find((s) => s.code === selectedSkillCode)?.displayName ?? '自动识别' }}<span class="capability-hint">（可选）</span></summary>
                <div class="capability-options">
                  <el-check-tag :checked="selectedSkillCode === null" @change="selectedSkillCode = null">自动识别</el-check-tag>
                  <el-check-tag v-for="skill in skills" :key="skill.code" :checked="selectedSkillCode === skill.code" @change="selectedSkillCode = selectedSkillCode === skill.code ? null : skill.code">{{ skill.displayName }}</el-check-tag>
                </div>
              </details>
            <el-input v-model="question" type="textarea" :rows="3" maxlength="4000" show-word-limit
              :placeholder="activeRun?.status === 'WAITING_FOR_USER_INPUT' ? '请输入你的回复...' : (activeRun?.status === 'PAUSED' ? '输入“继续”，接着完成当前任务' : '例如：检查本周进度和高风险事项，并给出来源')"
              @keydown.ctrl.enter.prevent="activeRun?.status === 'WAITING_FOR_USER_INPUT' ? continueRunHandler() : send()" />
            <div class="composer-actions">
              <el-button v-if="activeRun?.status === 'WAITING_FOR_USER_INPUT'"
                type="primary" :loading="sending" :disabled="!question.trim()"
                @click="continueRunHandler">
                回复 Agent（Ctrl+Enter）
              </el-button>
              <el-button v-else type="primary" :loading="sending" :disabled="!question.trim()" @click="send">发送（Ctrl+Enter）</el-button>
              <el-button v-if="canPause" data-test="agent-pause" plain :loading="pauseBusy" @click="pauseActiveRun">暂停</el-button>
              <el-button v-if="activeRun && !activeRunState?.terminal && activeRun?.status !== 'WAITING_FOR_USER_INPUT'" type="danger" plain @click="cancelActiveRun">结束本次运行</el-button>
            </div>
            </div>
          </main>
      <aside v-if="showInspector" class="agent-inspector" aria-label="运行详情" :data-view="mobileView">
        <button class="inspector-close" type="button" aria-label="关闭详情" data-test="agent-inspector-close" @click="showInspector = false">×</button>
        <div v-if="!activeRun" class="inspector-empty-state">
          <h2>运行详情</h2>
          <p>执行 Agent 后，这里会展示审批、规划和运行状态。</p>
        </div>
        <section v-if="activeRun && pendingApprovals.length" class="inspector-block inspector-primary">
          <h2>待审批（{{ pendingApprovals.length }}）</h2>
          <AgentApprovalCard v-for="item in pendingApprovals" :key="item.id" :approval="item" :members="members" :busy-action="approvalBusy?.id === item.id ? approvalBusy.action : null" @approve="approve" @reject="reject" />
        </section>
        <RunStatusCard
          v-if="activeRun"
          :run="activeRun"
          :state="activeRunState"
          :model="activeModel?.model ?? null"
          :sse-line="sseStatusLine"
          :sse-paused="activeRun.status === 'PAUSED'"
          :sending="sending"
          @retry="retryActiveRun"
        />
        <section v-if="activeRun" class="inspector-block">
          <h2>参考步骤与查询事实</h2>
          <AgentRunTimeline :plan="timeline.plan" :status="activeRun?.status" :events="timeline.events" />
        </section>
        <section v-if="hasPageContext" class="inspector-block">
          <h2>页面上下文</h2>
          <AgentContextChips :context="pageContext" @remove="removeContext" @clear="clearContext" />
        </section>
        <section v-if="activeRun && resolvedApprovals.length" class="inspector-block">
          <h2>已处理提案（{{ resolvedApprovals.length }}）</h2>
          <AgentApprovalCard v-for="item in resolvedApprovals" :key="item.id" :approval="item" :members="members" :busy-action="approvalBusy?.id === item.id ? approvalBusy.action : null" @approve="approve" @reject="reject" />
        </section>
        <details v-if="activeRun" class="inspector-block tech-details inspector-diag">
          <summary>诊断与资源</summary>
          <p v-if="runDetail?.modelConfiguration" class="inspector-meta">{{ runDetail.modelConfiguration.provider }} · {{ runDetail.modelConfiguration.model }} · {{ runDetail.modelConfiguration.mode }}</p>
          <p v-if="runDetail?.modelConfiguration" class="inspector-meta">单次输出上限 {{ runDetail.modelConfiguration.maxOutputTokens }} · {{ runDetail.modelConfiguration.budgetEnforced ? '请求已设置上限' : '提供商请求不支持该上限' }}</p>
          <p v-if="runDetail?.recoveryCounters" class="inspector-meta">模型重试 {{ runDetail.recoveryCounters.MODEL_RETRY ?? 0 }} · 格式修复 {{ runDetail.recoveryCounters.FORMAT_REPAIR ?? 0 }} · 参数纠正 {{ runDetail.recoveryCounters.PARAMETER_CORRECTION ?? 0 }}</p>
          <p class="inspector-meta">{{ tokenUsageLine }}</p>
          <p class="inspector-meta">Sequence {{ runDetail?.lastEventSequence }} · SSE {{ timeline.connected ? '已连接' : '未连接' }}</p>
          <p v-if="sseDiagnostics.category" class="inspector-meta" data-test="agent-sse-diagnostic">
            断线诊断：{{ sseStatusLine }}
          </p>
        </details>
      </aside>
    </div>
    <AgentPlanningCards :project-id="projectId" :session-id="sessionId" :run-version="runDetail?.lastEventSequence" />
    <el-drawer :model-value="Boolean(source)" title="引用原文" @close="source = null">
      <DocumentBodyReader v-if="source" :project-id="projectId" :document-id="source.documentId" :chunk-id="source.chunkId" />
    </el-drawer>
  </section>
</template>

<style scoped>
.agent-page{display:flex;flex-direction:column;gap:12px;min-height:calc(100dvh - 116px);padding-top:0;padding-bottom:16px}
/* 三栏工作区：会话栏可收起，中栏为阅读主区，右栏服务审批与状态。
   固定工作区高度：滚动只发生在消息区/侧栏内部，页面级双滚动消失 */
.agent-workspace{display:grid;grid-template-columns:228px minmax(0,1fr) 316px;align-items:stretch;background:var(--color-surface);border:1px solid var(--color-border);border-radius:var(--radius-card);overflow:hidden;height:calc(100dvh - 240px);min-height:520px}
.agent-workspace.hide-sessions{grid-template-columns:minmax(0,1fr) 316px}
.agent-workspace.hide-sessions.hide-inspector{grid-template-columns:minmax(0,1fr)}
.agent-workspace.hide-inspector{grid-template-columns:228px minmax(0,1fr)}
.agent-sessions,.agent-inspector{background:var(--color-surface);padding:14px 12px;display:flex;flex-direction:column;gap:6px;min-height:0;border:0;border-radius:0}
.agent-rail{border-right:1px solid var(--color-border)}
.agent-inspector{border-left:1px solid var(--color-border);background:var(--color-surface-raised)}
.agent-sessions{min-height:0;overflow-y:auto}
.new-session{width:100%;margin-bottom:4px}
.conversation-empty{display:grid;gap:8px;justify-items:center;text-align:center;padding:48px 20px}
.conversation-empty h3{margin:0;font-size:16px}
.conversation-empty p{margin:0;color:var(--color-text-secondary);font-size:13px}
.suggested-prompts{display:flex;gap:8px;flex-wrap:wrap;justify-content:center}
.composer{display:grid;gap:8px;position:sticky;bottom:0;background:var(--color-surface);padding-top:8px;border-top:1px solid var(--color-border)}
.capability{font-size:13px;color:var(--color-text-secondary)}
.capability summary{cursor:pointer;list-style:none}
.capability-hint{color:var(--color-text-muted);font-size:12px;margin-left:4px}
.capability-options{display:flex;gap:6px;flex-wrap:wrap;margin-top:8px}
.inspector-empty-state{display:grid;gap:6px;padding:24px 8px;text-align:center}
.inspector-empty-state h2{font-size:14px;margin:0}
.inspector-empty-state p{font-size:13px;color:var(--color-text-secondary);margin:0}
.session-row{position:relative;display:grid;grid-template-columns:minmax(0,1fr) auto;gap:2px;align-items:start;padding:4px;border-radius:10px}
.overflow-trigger{display:inline-flex;align-items:center;justify-content:center;width:28px;height:28px;margin-top:6px;border:0;border-radius:8px;background:transparent;color:var(--color-text-secondary);cursor:pointer;flex:none}
.overflow-trigger:hover{background:var(--el-fill-color)}
.danger-item{color:var(--color-danger)}
.session-row:hover{background:var(--el-fill-color)}
.session-row.active{background:var(--el-color-primary-light-9)}
.session-row.active .session{color:var(--el-color-primary)}
.session{width:100%;height:40px;min-height:40px;border:0;border-radius:8px;padding:0 10px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;text-align:left;background:transparent;cursor:pointer}
.session.active{background:var(--el-color-primary-light-8);color:var(--el-color-primary)}
/* 对话主区：稳定的阅读宽度，不随两侧栏开关跳动 */
.conversation{min-height:0;padding:12px 24px 16px;display:grid;grid-template-rows:auto minmax(0,1fr) auto auto;gap:8px;background:var(--color-surface);border:1px solid var(--color-border);border-radius:12px}
.agent-workspace .conversation{border:0;border-radius:0;background:var(--color-surface)}
.agent-main{min-width:0;min-height:0;display:flex;flex-direction:column}
.conversation-head{display:flex;align-items:center;justify-content:flex-end;gap:8px;min-height:24px}
.model-chip{display:inline-flex;align-items:center;gap:6px;font-size:12px;color:var(--color-text-secondary);padding:2px 10px;border:1px solid var(--color-border);border-radius:999px;background:var(--color-surface-raised)}
.model-dot{width:6px;height:6px;border-radius:50%;background:var(--color-text-muted)}
.model-chip.live{color:var(--color-primary);border-color:var(--color-primary-soft);background:var(--color-primary-soft)}
.model-chip.live .model-dot{background:var(--color-primary);animation:pulse 1.2s infinite}
.messages-wrap{position:relative;display:grid;min-height:0;grid-template-rows:minmax(0,1fr)}
.messages{min-height:0;overflow:auto;scroll-behavior:smooth}
.conversation-title{font-size:13px;font-weight:600;color:var(--color-text);overflow:hidden;text-overflow:ellipsis;white-space:nowrap;max-width:32ch}
.sse-pill{display:inline-flex;align-items:center;font-size:12px;color:var(--color-warning);padding:2px 10px;border:1px solid rgba(173,118,40,.35);border-radius:999px;background:var(--color-warning-soft)}
.run-state-line{display:flex;align-items:center;gap:8px;font-size:13px;padding:6px 12px;border-radius:8px}
.run-state-line.info{color:var(--color-text-secondary);background:var(--el-fill-color-lighter)}
.run-state-line.warning{color:var(--color-warning);background:var(--color-warning-soft)}
.run-state-line.error{color:var(--color-danger);background:var(--color-danger-soft)}
.state-retry{margin-left:auto}
.new-content-pill{position:absolute;bottom:8px;left:50%;transform:translateX(-50%);border:1px solid var(--color-border);background:var(--color-surface);color:var(--color-text-secondary);font-size:12px;padding:4px 14px;border-radius:999px;cursor:pointer;box-shadow:var(--shadow-card);z-index:5}
.new-content-pill:hover{color:var(--color-primary);border-color:var(--color-primary-soft)}
/* 消息排版：助手回答为通栏阅读正文（稳定行宽），用户消息保持紧凑右对齐区分 */
.msg{margin:16px 0}
.msg.user{display:flex;justify-content:flex-end}
.msg.user .user-text{max-width:78%;margin:0;padding:10px 14px;border-radius:14px 14px 4px 14px;background:var(--el-color-primary-light-9);white-space:pre-wrap;font-size:14px;line-height:1.65}
.msg.user{scroll-margin-bottom:8px}
/* 阅读正文：取消窄气泡约束，max-width 限行宽，标题分级还原层级 */
.answer-body{max-width:76ch;font-size:15px;line-height:1.8;color:var(--color-text);overflow-wrap:break-word}
.answer-body :deep(p){margin:12px 0}
.answer-body :deep(h1){margin:26px 0 12px;font-size:21px;line-height:1.35;font-weight:650}
.answer-body :deep(h2){margin:24px 0 10px;font-size:17.5px;line-height:1.4;font-weight:650;padding-bottom:5px;border-bottom:1px solid var(--color-border)}
.answer-body :deep(h3){margin:18px 0 8px;font-size:15.5px;line-height:1.45;font-weight:650}
.answer-body :deep(h4),.answer-body :deep(h5),.answer-body :deep(h6){margin:14px 0 6px;font-size:14.5px;color:var(--color-text-secondary);font-weight:650}
.answer-body :deep(h1:first-child),.answer-body :deep(h2:first-child),.answer-body :deep(h3:first-child){margin-top:4px}
.answer-body :deep(ul),.answer-body :deep(ol){margin:10px 0;padding-left:28px}
.answer-body :deep(li){margin:6px 0}
.answer-body :deep(li::marker){color:var(--color-text-muted)}
.answer-body :deep(li>p){margin:4px 0}
.answer-body :deep(strong){font-weight:650}
.answer-body :deep(hr){border:0;border-top:1px solid var(--color-border);margin:18px 0}
/* 追问块：与正文分开的独立确认区，选项从 1 开始独立编号 */
.question-block{max-width:76ch;margin:12px 0;padding:12px 16px;border:1px solid var(--color-primary-soft);border-radius:10px;background:var(--el-color-primary-light-9)}
.question-label{margin:0 0 6px;font-size:12.5px;font-weight:650;color:var(--color-primary);letter-spacing:.02em}
.question-options{margin:0;padding-left:24px}
.question-options li{margin:5px 0;font-size:14.5px;line-height:1.7}
.answer-body :deep(table){border-collapse:collapse;margin:12px 0;font-size:13px;max-width:100%;display:block;overflow-x:auto}
.answer-body :deep(th),.answer-body :deep(td){border:1px solid var(--color-border);padding:6px 12px;text-align:left;vertical-align:top}
.answer-body :deep(th){background:var(--el-fill-color-lighter);font-weight:600;white-space:nowrap}
.answer-body :deep(code){background:var(--el-fill-color-light);padding:1px 6px;border-radius:4px;font-size:13px}
.answer-body :deep(pre){background:#0f172a;color:#e2e8f0;padding:14px;border-radius:8px;overflow-x:auto;font-size:13px;line-height:1.6}
.answer-body :deep(pre code){background:transparent;padding:0;color:inherit}
.answer-body :deep(blockquote){margin:12px 0;padding:8px 14px;background:var(--el-fill-color-lighter);color:var(--color-text-secondary);border-radius:8px}
.answer-body :deep(blockquote p){margin:4px 0}
/* 来源与推断：可折叠、次级视觉，不与正文争抢 */
.evidence{margin:8px 0;max-width:76ch}
.evidence>summary{cursor:pointer;list-style:none;font-size:12.5px;color:var(--color-text-secondary);display:inline-flex;align-items:center;gap:4px;padding:3px 10px;border:1px solid var(--color-border);border-radius:999px;background:var(--color-surface-raised)}
.evidence>summary::-webkit-details-marker{display:none}
.evidence>summary:hover{color:var(--color-primary);border-color:var(--color-primary-soft)}
.evidence-list{margin:8px 0 0;padding:0 0 0 4px;list-style:none;display:grid;gap:8px}
.evidence-list li{font-size:13px;padding:8px 12px;border:1px solid var(--color-border);border-radius:8px;background:var(--color-surface)}
.evidence-list li p{margin:4px 0 0;color:var(--color-text-secondary)}
.empty{text-align:center;padding:80px;color:var(--el-text-color-secondary)}
.waiting-for-input{margin:8px 0}
.composer-actions{display:flex;gap:8px;flex-wrap:wrap}
.agent-inspector{min-height:0;overflow-y:auto}
.inspector-block{display:grid;gap:8px;padding-bottom:12px;margin-bottom:4px;border-bottom:1px solid var(--color-border)}
.inspector-block:last-child{border-bottom:0}
.inspector-block h2{font-size:12.5px;color:var(--color-text-secondary);margin:0;font-weight:600;letter-spacing:.02em}
.inspector-primary{background:var(--el-color-warning-light-9);border:1px solid var(--el-color-warning-light-7);border-radius:10px;padding:10px}
.inspector-primary h2{color:var(--el-color-warning-dark-2)}
.inspector-empty{color:var(--color-text-muted);font-size:13px;margin:0}
.inspector-meta{color:var(--color-text-muted);font-size:12px;margin:0}
.inspector-meta-lines{display:grid;gap:2px}
.inspector-actions{display:flex;gap:8px}
.inspector-diag{color:var(--color-text-muted)}
.inspector-diag summary{cursor:pointer;font-size:12.5px;color:var(--color-text-secondary)}
.rail-toggle,.inspector-toggle{display:inline-flex}
/* 关闭按钮只属于中等窗口的详情覆盖层：全尺寸/窄屏不显示 */
.inspector-close{display:none}
.dot{display:inline-block;width:8px;height:8px;border-radius:50%;background:#d1d5db;margin-right:6px;flex:none}
.dot.run{background:var(--color-primary);box-shadow:0 0 0 3px rgba(37,99,235,.15)}
.dot.wait{background:var(--color-warning)}
.dot.fail{background:var(--color-danger)}
.dot.done{background:#16a34a}
.session-title{display:block;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.session-meta{display:flex;gap:8px;font-size:12px;color:var(--el-text-color-secondary);align-items:center}
/* 过程区：摘要条常显 + 执行详情默认折叠；活动行渲染在 ActivityGroupList 内 */
.activity-group{margin:10px 0;display:grid;gap:6px}
.activity-detail-toggle{border:0}
.activity-detail-toggle>summary{display:flex;gap:8px;align-items:center;padding:4px 10px;cursor:pointer;list-style:none;border-radius:8px;color:var(--color-text-secondary);font-size:12.5px}
.activity-detail-toggle>summary::-webkit-details-marker{display:none}
.activity-detail-toggle>summary:hover{background:var(--el-fill-color-lighter);color:var(--color-text)}
.detail-toggle-label{font-weight:550}
.group-items{display:grid;gap:4px}
/* 流式正文预览：与最终回答同一排版；渲染回退时安全文本插值 + 光标 */
.streaming-preview p{margin:0;white-space:pre-wrap;overflow-wrap:anywhere}
.preview-html :deep(p){margin:0 0 8px}
.preview-html :deep(p:last-child){margin-bottom:0}
.preview-cursor{display:inline-block;margin-left:2px;color:var(--color-primary);animation:pulse 1.2s infinite}
.preview-note{font-size:12px;color:var(--color-text-muted);margin-top:4px}
.activity-meta{display:flex;gap:8px;font-size:12px;color:var(--el-text-color-secondary);flex:none}
.tech-details pre{background:var(--el-fill-color-lighter);border:1px solid var(--color-border);color:var(--color-text-secondary);padding:10px;border-radius:8px;overflow-x:auto;font-size:12px;margin:6px 0}
.tech-details summary{cursor:pointer;font-size:12px;color:var(--color-text-muted)}
@keyframes pulse{0%{opacity:1}50%{opacity:.35}100%{opacity:1}}
.mobile-switch{display:none}
/* 中等桌面窗口：对话优先。会话栏并入可开合的详情抽屉逻辑——对话始终占满剩余宽度，
   详情作为右侧覆盖层（含可点击的关闭入口），不再出现 300px 窄列 + 400px 固定详情双层遮挡 */
@media(max-width:1280px) and (min-width:761px){
  .agent-workspace{grid-template-columns:220px minmax(0,1fr)}
  .agent-workspace.hide-sessions{grid-template-columns:minmax(0,1fr)}
  .agent-workspace.hide-inspector{grid-template-columns:220px minmax(0,1fr)}
  .agent-workspace.hide-sessions.hide-inspector{grid-template-columns:minmax(0,1fr)}
  .agent-inspector{position:fixed;top:0;right:0;bottom:0;width:min(400px,92vw);z-index:60;background:var(--color-surface);border-left:1px solid var(--color-border);box-shadow:-12px 0 32px rgba(15,23,42,.12);padding:16px;overflow-y:auto;max-height:none}
  .inspector-close{position:absolute;top:10px;right:12px;width:32px;height:32px;display:grid;place-items:center;font-size:20px;line-height:1;color:var(--color-text-secondary);cursor:pointer;border-radius:8px;background:var(--color-surface-raised);border:1px solid var(--color-border)}
  .inspector-close:hover{color:var(--color-text);background:var(--el-fill-color)}
  .agent-workspace.hide-inspector .agent-inspector{display:none}
}
@media(max-width:760px){
  .mobile-switch{display:block;margin-bottom:8px}
  .rail-toggle,.inspector-toggle{display:none}
  .agent-workspace,.agent-workspace.hide-inspector,.agent-workspace.hide-sessions,.agent-workspace.hide-sessions.hide-inspector{grid-template-columns:1fr}
  .agent-workspace[data-view="sessions"] .conversation,.agent-workspace[data-view="sessions"] .agent-inspector{display:none}
  .agent-workspace[data-view="chat"] .agent-sessions,.agent-workspace[data-view="chat"] .agent-inspector{display:none}
  .agent-workspace[data-view="inspector"] .agent-sessions,.agent-workspace[data-view="inspector"] .conversation{display:none}
  .conversation{padding:10px 14px 14px}
  .answer-body{max-width:100%;font-size:14px}
  .msg.user .user-text{max-width:88%}
}
</style>
