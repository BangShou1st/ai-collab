<script setup lang="ts">
import { MoreFilled } from '@element-plus/icons-vue'
import { ref } from 'vue'
import DocumentBodyReader from '../document/DocumentBodyReader.vue'
import AgentPlanningCards from './AgentPlanningCards.vue'
import PageHeader from '../../shared/PageHeader.vue'
import EmptyState from '../../shared/EmptyState.vue'
import AgentContextChips from './AgentContextChips.vue'
import AgentRunTimeline from './AgentRunTimeline.vue'
import AgentApprovalCard from './AgentApprovalCard.vue'
import SemanticDiff from '../../shared/SemanticDiff.vue'
import { useAgentWorkspace } from './use-agent-workspace'

const {
  projectId, currentUserId, mobileView, showInspector, runTone,
  pendingApprovals, resolvedApprovals, sessions, summaries, sessionId, messages,
  approvals, runDetail, activities, conversationBlocks,
  summaryOf, isCreator, relativeTime, runStatusLabel, evidenceTitle, evidenceDetail,
  statusDot, members, skills, selectedSkillCode, question, busy, sending,
  activeRun, timeline, activeRunState, pageContext, hasPageContext,
  newSession, renameSession, deleteSession, send, removeContext, clearContext,
  approve, reject, approvalBusy, continueRunHandler, retryActiveRun, cancelActiveRun, time,
} = useAgentWorkspace()
const source = ref<{ documentId: string; chunkId: string } | null>(null)
function sourceIdentity(c: unknown): { documentId: string; chunkId: string } | null {
  if (!c || typeof c !== 'object') return null
  const value = c as Record<string, unknown>
  return typeof value.documentId === 'string' && typeof value.chunkId === 'string' ? { documentId: value.documentId, chunkId: value.chunkId } : null
}
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
                    <li v-for="(c, i) in block.message.citations" :key="i"><strong>{{ evidenceTitle(c, `来源 ${i + 1}`) }}</strong><p v-if="evidenceDetail(c)">{{ evidenceDetail(c) }}</p><el-button v-if="sourceIdentity(c)" text @click="source = sourceIdentity(c)">查看原文片段</el-button></li>
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
          <AgentApprovalCard v-for="item in pendingApprovals" :key="item.id" :approval="item" :members="members" :busy-action="approvalBusy?.id === item.id ? approvalBusy.action : null" @approve="approve" @reject="reject" />
        </section>
        <section v-if="activeRun" class="inspector-block">
          <h2>执行记录与参考步骤</h2>
          <AgentRunTimeline :plan="timeline.plan" :status="activeRun?.status" :events="timeline.events" />
        </section>
        <section v-if="activeRun" class="inspector-block">
            <h2>Resources</h2>
            <p v-if="runDetail?.modelConfiguration" class="inspector-meta">{{ runDetail.modelConfiguration.provider }} · {{ runDetail.modelConfiguration.model }} · {{ runDetail.modelConfiguration.mode }}</p>
            <p v-if="runDetail?.modelConfiguration" class="inspector-meta">输出预算 {{ runDetail.modelConfiguration.maxOutputTokens }} · {{ runDetail.modelConfiguration.budgetEnforced ? '请求已设置上限' : '提供商请求不支持该上限' }}</p>
            <p v-if="runDetail?.recoveryCounters" class="inspector-meta">模型重试 {{ runDetail.recoveryCounters.MODEL_RETRY ?? 0 }} · 格式修复 {{ runDetail.recoveryCounters.FORMAT_REPAIR ?? 0 }} · 参数纠正 {{ runDetail.recoveryCounters.PARAMETER_CORRECTION ?? 0 }}</p>
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
          <AgentApprovalCard v-for="item in resolvedApprovals" :key="item.id" :approval="item" :members="members" :busy-action="approvalBusy?.id === item.id ? approvalBusy.action : null" @approve="approve" @reject="reject" />
        </section>
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
.dot.run{background:var(--color-primary);box-shadow:0 0 0 3px rgba(37,99,235,.15)}
.dot.wait{background:var(--color-warning)}
.dot.fail{background:var(--color-danger)}
.dot.done{background:#16a34a}
.session-title{display:block;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.session-meta{display:flex;gap:8px;font-size:12px;color:var(--el-text-color-secondary)}
.activity-group{display:grid;gap:6px;margin:12px 0}
.activity{display:flex;gap:10px;align-items:flex-start;padding:8px 10px;border-radius:10px;background:var(--el-fill-color-lighter)}
.activity.read,.activity.search{padding:6px 10px}
.activity.proposal{background:rgba(37,99,235,.06);border:1px solid rgba(37,99,235,.18)}
.activity.approval{background:rgba(217,119,6,.08);border:1px solid rgba(217,119,6,.25)}
.activity.failure,.activity.failed{background:rgba(220,38,38,.06);border:1px solid rgba(220,38,38,.2)}
.activity-mark{width:8px;height:8px;border-radius:50%;background:var(--color-text-muted);margin-top:5px;flex:none}
.activity.running .activity-mark{background:var(--color-primary);animation:pulse 1.2s infinite}
.activity.done .activity-mark,.activity.success .activity-mark{background:#16a34a}
.activity.failed .activity-mark,.activity.failure .activity-mark{background:var(--color-danger)}
.activity.waiting .activity-mark,.activity.approval .activity-mark{background:var(--color-warning)}
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
