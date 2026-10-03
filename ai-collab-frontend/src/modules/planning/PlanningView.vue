<script setup lang="ts">
import {
  planStatusLabel,
  priorityLabel,
  versionSourceLabel,
  fieldLabel,
} from './planning-labels'
import { usePlanningView } from './use-planning-view'
import PlanningIssuePanel from './components/PlanningIssuePanel.vue'
import PlanningEventTimeline from './components/PlanningEventTimeline.vue'
import PageHeader from '../../shared/PageHeader.vue'
import EmptyState from '../../shared/EmptyState.vue'
import { repairRuleLabel } from './planning-failure'

const {
  projectId, plans, selected, permissions, draft, versions, selectedVersionId,
  expandedMilestones, createVisible, checked, saveDialogVisible, saveComment,
  aiConfigured, canCreate, pendingConfirmation, documents, members, project,
  structuredIssues, repairDiagnostics, adoptAssignee, events, form, dirty, editingLatest, canEditCurrent,
  confirmationSummary, targetNames, getTaskCount, getUnassignedCount,
  failureMessage, hasPendingItems, autoExpandPendingMilestones,
  formStartDisabled, formDueDisabled, milestoneDateDisabled,
  taskStartDateDisabled, taskDueDateDisabled,
  open, openVersion, requestVersion, create, action, restore, save, confirmSave,
  manualEdit, locate, removePlan, confirm, refresh, repairTask,
  addMilestone, addTask, removeTask, removeMilestone, addAssumption, addRisk,
  memberName, toggleMilestone,
  historyReadOnlyMessage, dependencyWouldCycle,
} = usePlanningView()
</script>

<template>
  <main class="workspace-page">
    <PageHeader eyebrow="AI 辅助" title="AI 任务规划">
      <template #actions><router-link v-if="selected" :to="`/projects/${projectId}/agent?plan=${selected.id}`"><el-button text type="primary">交给 Agent</el-button></router-link><el-button v-if="canCreate" type="primary" @click="createVisible = true">创建规划</el-button></template>
    </PageHeader>
    <section class="planning-layout">
      <div class="planning-list-panel">
        <button v-for="plan in plans" :key="plan.id" class="planning-list-item" :class="{ 'is-active': selected?.id === plan.id }" @click="open(plan)">
          <span class="planning-item-title">{{ plan.title }}</span>
          <span class="planning-item-meta"><span class="status-dot" :class="plan.status === 'CONFIRMED' ? 'done' : plan.status === 'FAILED' ? 'fail' : 'run'" /><span>{{ planStatusLabel(plan.status) }}</span><span>·</span><span>v{{ plan.latestVersionNo }}</span></span>
          <small class="planning-item-desc">{{ plan.goal }}</small>
        </button>
        <EmptyState
          v-if="!plans.length"
          compact
          title="当前还没有规划"
          description="描述你的目标，AI 会生成一份草案"
        />
      </div>
      <el-card v-if="!selected" class="planning-detail-empty">
        <EmptyState
          compact
          title="选择一份规划查看详情"
          description="将项目目标转化为可审核的任务方案。草案 → 审核 → 确认，只有确认后才会写入真实项目任务。"
        />
        <div class="planning-empty-actions">
          <el-button v-if="canCreate" type="primary" @click="createVisible = true">创建规划</el-button>
        </div>
        <el-alert
          v-if="!aiConfigured"
          title="尚未配置 AI"
          description="配置个人 AI 后即可生成规划"
          type="info"
          show-icon
          :closable="false"
        >
          <template #default>
            <router-link to="/settings/ai"><el-button text type="primary">前往 AI 设置</el-button></router-link>
          </template>
        </el-alert>
      </el-card>
      <el-card v-if="selected">
        <template #header><div class="actions"><strong>{{ selected.title }}</strong>
          <el-button v-if="permissions?.canCancel && editingLatest" @click="action('cancel')">取消</el-button>
          <el-button v-if="permissions?.canRetryDetail && editingLatest" @click="action('retry-detail')">重试细节</el-button>
          <el-button v-if="permissions?.canRegenerate && editingLatest" @click="action('regenerate')">重新生成</el-button>
          <el-button v-if="permissions?.canDelete" type="danger" @click="removePlan">删除规划</el-button>
        </div></template>
        <el-alert v-if="failureMessage" :title="failureMessage" type="warning" />
        <ul v-if="failureMessage && repairDiagnostics.length" aria-label="修复失败原因">
          <li v-for="(issue, index) in repairDiagnostics" :key="index">
            {{ targetNames[issue.targetTempKey ?? ''] ?? issue.targetTempKey ?? '规划' }} · {{ fieldLabel(issue.field) }}：{{ repairRuleLabel(issue.code) }}
          </li>
        </ul>
        <PlanningIssuePanel
          v-if="structuredIssues.length > 0"
          :issues="structuredIssues"
          :target-names="targetNames"
          :actions-enabled="editingLatest"
          @locate="locate"
          @edit="manualEdit"
        />
        <PlanningEventTimeline v-if="events.length > 0" :events="events" />
        <el-select data-testid="planning-version-select" :model-value="selectedVersionId" @change="requestVersion"><el-option v-for="v in versions" :key="v.id" :label="`v${v.versionNo} ${versionSourceLabel(v.sourceType)}`" :value="v.id" /></el-select>
        <template v-if="draft">
          <el-alert v-if="!editingLatest" :title="historyReadOnlyMessage" type="info" />
          <label>规划摘要</label><el-input v-model="draft.summary" type="textarea" :readonly="!permissions?.canEdit || !editingLatest" placeholder="规划摘要" />
          <h3>假设</h3><div v-for="(_, index) in draft.assumptions" :key="`a-${index}`"><label>假设 {{ index + 1 }}</label><el-input v-model="draft.assumptions[index]" :readonly="!canEditCurrent" /><el-button v-if="canEditCurrent" @click="draft.assumptions.splice(index, 1)">删除</el-button></div><el-button v-if="canEditCurrent" @click="addAssumption">添加假设</el-button>
          <h3>风险</h3><div v-for="(_, index) in draft.risks" :key="`r-${index}`"><label>风险 {{ index + 1 }}</label><el-input v-model="draft.risks[index]" :readonly="!canEditCurrent" /><el-button v-if="canEditCurrent" @click="draft.risks.splice(index, 1)">删除</el-button></div><el-button v-if="canEditCurrent" @click="addRisk">添加风险</el-button>

          <!-- 里程碑列表 -->
          <div class="milestone-list">
            <div v-for="m in draft.milestones" :key="m.tempKey" class="milestone-section" :class="{ 'milestone-pending': hasPendingItems(m.tempKey) }">
              <div class="milestone-header" :id="`planning-${m.tempKey}-entity`" @click="toggleMilestone(m.tempKey)">
                <div class="milestone-expand-icon">
                  <span v-if="expandedMilestones.includes(m.tempKey)">▼</span>
                  <span v-else>▶</span>
                </div>
                <div class="milestone-title-row">
                  <h3 class="milestone-title">{{ m.title }}</h3>
                  <span class="milestone-date" v-if="m.targetDate">目标：{{ m.targetDate }}</span>
                </div>
                <div class="milestone-stats">
                  <span class="stat-item">{{ getTaskCount(m.tempKey) }} 个任务</span>
                  <span v-if="getUnassignedCount(m.tempKey) > 0" class="stat-unassigned">
                    {{ getUnassignedCount(m.tempKey) }} 未分配
                  </span>
                </div>
                <div class="milestone-actions" v-if="canEditCurrent" @click.stop>
                  <el-button size="small" @click="addTask(m.tempKey)">添加任务</el-button>
                  <el-button size="small" type="danger" @click="removeMilestone(m.tempKey)">删除</el-button>
                </div>
              </div>

              <!-- 里程碑详情 -->
              <div v-if="expandedMilestones.includes(m.tempKey)" class="milestone-details">
                <div class="detail-row">
                  <label>标题</label>
                  <el-input v-model="m.title" :readonly="!permissions?.canEdit || !editingLatest" />
                </div>
                <div class="detail-row">
                  <label>目标</label>
                  <el-input v-model="m.objective" type="textarea" :readonly="!canEditCurrent" />
                </div>
                <div class="detail-row">
                  <label>描述</label>
                  <el-input v-model="m.description" type="textarea" :readonly="!canEditCurrent" placeholder="里程碑描述" />
                </div>
                <div class="detail-row">
                  <label>目标日期</label>
                  <span :id="`planning-${m.tempKey}-targetDate`">
                            <el-date-picker v-model="m.targetDate" value-format="YYYY-MM-DD" :disabled="!canEditCurrent" :disabled-date="milestoneDateDisabled" />
                  </span>
                </div>
                <div class="detail-row">
                  <label>参考来源</label>
                  <el-select v-model="m.sourceRefs" multiple :disabled="!canEditCurrent">
                    <el-option v-for="source in draft.sources" :key="source.ref" :label="source.documentName" :value="source.ref" />
                  </el-select>
                </div>
              </div>

              <!-- 任务列表 -->
              <div v-if="expandedMilestones.includes(m.tempKey)" class="task-list">
                <div v-for="task in draft.tasks.filter(t => t.milestoneTempKey === m.tempKey)"
                     :id="`planning-${task.tempKey}-entity`"
                     :key="task.tempKey"
                     class="task-card"
                     :class="{ 'task-unassigned': !task.assigneeId }">
                  <div class="task-header">
                    <span class="task-title">{{ task.title }}</span>
                    <el-tag v-if="!task.assigneeId" type="warning" size="small">未分配</el-tag>
                  </div>

                  <div class="task-details">
                    <div class="detail-row">
                      <label>标题</label>
                      <el-input v-model="task.title" :readonly="!permissions?.canEdit || !editingLatest" />
                    </div>
                    <div class="detail-row">
                      <label>描述</label>
                      <el-input :id="`planning-${task.tempKey}-description`" v-model="task.description" type="textarea" :readonly="!permissions?.canEdit || !editingLatest" />
                    </div>
                    <div class="detail-row">
                      <label>目标</label>
                      <el-input v-model="task.objective" :readonly="!canEditCurrent" />
                    </div>
                    <div class="detail-row-inline">
                      <div class="detail-item">
                        <label>优先级</label>
                        <span :id="`planning-${task.tempKey}-priority`">
                          <el-select v-model="task.priority" :disabled="!canEditCurrent">
                            <el-option v-for="p in ['LOW','MEDIUM','HIGH','URGENT']" :key="p" :label="priorityLabel(p)" :value="p" />
                          </el-select>
                        </span>
                      </div>
                      <div class="detail-item">
                        <label>预估工时</label>
                        <el-input-number v-model="task.estimatedHours" :min="0.5" :max="80" :disabled="!canEditCurrent" />
                      </div>
                    </div>
                    <div class="detail-row-inline">
                      <div class="detail-item">
                        <label>开始日期</label>
                        <span :id="`planning-${task.tempKey}-startDate`">
                              <el-date-picker v-model="task.startDate" value-format="YYYY-MM-DD" :disabled="!canEditCurrent" :disabled-date="taskStartDateDisabled(task)" />
                        </span>
                      </div>
                      <div class="detail-item">
                        <label>截止日期</label>
                        <span :id="`planning-${task.tempKey}-dueDate`">
                              <el-date-picker v-model="task.dueDate" value-format="YYYY-MM-DD" :disabled="!canEditCurrent" :disabled-date="taskDueDateDisabled(task)" />
                        </span>
                      </div>
                    </div>
                    <div class="detail-row">
                      <label>AI 建议负责人</label>
                      <small>{{ memberName(task.suggestedAssigneeId) }}</small>
                      <el-button v-if="task.suggestedAssigneeId && task.assigneeId !== task.suggestedAssigneeId"
                        :disabled="!canEditCurrent || !members.some(member => member.userId === task.suggestedAssigneeId)"
                        @click="adoptAssignee(task)">采纳建议负责人</el-button>
                      <small v-if="task.suggestedAssigneeId && !task.assigneeId">采纳并保存后才会成为正式负责人</small>
                    </div>
                    <div class="detail-row">
                      <label>负责人</label>
                      <el-select v-model="task.assigneeId" clearable :disabled="!canEditCurrent">
                        <el-option v-for="member in members" :key="member.userId" :label="member.displayName" :value="member.userId" />
                      </el-select>
                    </div>
                    <div class="detail-row">
                      <label>前置任务</label>
                      <el-select v-model="task.dependencyTempKeys" multiple :disabled="!canEditCurrent">
                        <el-option v-for="candidate in draft.tasks.filter(candidate => candidate.tempKey !== task.tempKey)"
                                   :key="candidate.tempKey"
                                   :label="candidate.title"
                                   :value="candidate.tempKey"
                                   :disabled="dependencyWouldCycle(draft, task.tempKey, candidate.tempKey)" />
                      </el-select>
                    </div>
                    <div class="detail-row">
                      <label>参考来源</label>
                      <el-select v-model="task.sourceRefs" multiple :disabled="!canEditCurrent">
                        <el-option v-for="source in draft.sources" :key="source.ref" :label="source.documentName" :value="source.ref" />
                      </el-select>
                    </div>
                  </div>

                  <div class="task-actions" v-if="canEditCurrent">
                    <el-button v-if="permissions?.canPartialRegenerate && editingLatest" size="small" @click="repairTask(task.tempKey)">AI 局部修复</el-button>
                    <el-button type="danger" size="small" @click="removeTask(task.tempKey)">删除任务</el-button>
                  </div>
                </div>
              </div>
            </div>
          </div>
          <el-button v-if="canEditCurrent" @click="addMilestone">添加里程碑</el-button>
          <div class="actions"><el-button v-if="permissions?.canEdit && editingLatest" :disabled="!dirty" @click="save">保存新版本</el-button>
            <el-button v-if="permissions?.canRestore && !editingLatest" @click="restore">恢复为新版本</el-button>
            <template v-if="permissions?.canConfirm && editingLatest"><span>将创建 {{ confirmationSummary.milestones }} 里程碑 / {{ confirmationSummary.tasks }} 任务 / {{ confirmationSummary.dependencies }} 依赖；未分配 {{ confirmationSummary.unassigned }}</span><el-checkbox v-model="checked">我已检查规划</el-checkbox><el-button type="success" :disabled="!checked || dirty" @click="confirm">确认并创建任务</el-button></template>
          </div>
        </template>
      </el-card>
    </section>
    <el-dialog v-model="createVisible" title="创建规划"><el-form label-position="top" @submit.prevent="create">
      <el-form-item label="标题"><el-input v-model="form.title" /></el-form-item><el-form-item label="目标"><el-input v-model="form.goal" type="textarea" /></el-form-item>
      <el-form-item label="约束"><el-input v-model="form.constraints" /></el-form-item><el-form-item label="开始"><el-date-picker v-model="form.planStartDate" value-format="YYYY-MM-DD" :disabled-date="formStartDisabled" /></el-form-item>
      <el-form-item label="截止"><el-date-picker v-model="form.planDueDate" value-format="YYYY-MM-DD" :disabled-date="formDueDisabled" /></el-form-item><el-form-item label="最大任务数"><el-input-number v-model="form.maxTaskCount" :min="1" :max="40" :step="1" controls-position="right" /></el-form-item>
      <el-form-item label="可用参考文档（最多 10 个）"><el-select v-model="form.documentIds" multiple :multiple-limit="10"><el-option v-for="document in documents" :key="document.id" :label="document.displayName" :value="document.id" /></el-select></el-form-item>
      <el-button native-type="submit" type="primary">创建并生成</el-button>
    </el-form></el-dialog>
    <el-dialog v-model="saveDialogVisible" title="保存新版本">
      <el-form label-position="top">
        <el-form-item label="修改说明（可选）">
          <el-input v-model="saveComment" type="textarea" placeholder="描述本次修改的内容..." />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="saveDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="confirmSave">确认保存</el-button>
      </template>
    </el-dialog>
  </main>
</template>

<style scoped>
.milestone-list {
  margin-top: 16px;
}

.milestone-section {
  border: 1px solid var(--color-border);
  border-radius: 8px;
  margin-bottom: 16px;
  overflow: hidden;
}

.milestone-pending {
  background-color: var(--color-warning-soft);
  border-color: #ffcc02;
}

.milestone-header {
  background: var(--color-surface-raised);
  padding: 12px 16px;
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
  cursor: pointer;
  user-select: none;
}

.milestone-pending .milestone-header {
  background: #fff3cd;
}

.milestone-expand-icon {
  width: 20px;
  text-align: center;
  color: var(--color-text-secondary);
  font-size: 12px;
}

.milestone-title-row {
  display: flex;
  align-items: center;
  gap: 12px;
  flex: 1;
}

.milestone-title {
  margin: 0;
  font-size: 16px;
  font-weight: 600;
}

.milestone-date {
  color: var(--color-text-secondary);
  font-size: 13px;
}

.milestone-stats {
  display: flex;
  gap: 12px;
  font-size: 13px;
  color: var(--color-text-secondary);
}

.stat-unassigned {
  color: #ff9800;
  font-weight: 500;
}

.milestone-actions {
  display: flex;
  gap: 8px;
}

.milestone-details {
  padding: 16px;
  border-top: 1px solid var(--color-border);
}

.detail-row {
  margin-bottom: 12px;
}

.detail-row label {
  display: block;
  font-size: 13px;
  color: var(--color-text-secondary);
  margin-bottom: 4px;
}

.detail-row-inline {
  display: flex;
  gap: 16px;
  margin-bottom: 12px;
}

.detail-item {
  flex: 1;
}

.detail-item label {
  display: block;
  font-size: 13px;
  color: var(--color-text-secondary);
  margin-bottom: 4px;
}

.task-list {
  padding: 0 16px 16px;
}

.task-card {
  border: 1px solid var(--color-border);
  border-radius: 6px;
  padding: 12px;
  margin-top: 12px;
  background: white;
}

.task-unassigned {
  border: 1px solid var(--color-border);
  border-radius: 10px;
  background: var(--color-warning-soft);
}

.task-header {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
}

.task-title {
  font-weight: 500;
  font-size: 14px;
}

.task-details {
  padding: 0;
}

.task-actions {
  margin-top: 12px;
  padding-top: 12px;
  border-top: 1px solid #eee;
}

/* 选择框样式 - 使其更大更长 */
:deep(.el-select) {
  width: 100%;
}

:deep(.el-select .el-input__wrapper) {
  min-height: 36px;
}

/* 里程碑详情中的选择框 */
.milestone-details :deep(.el-select) {
  width: 100%;
}

/* 任务详情中的选择框 */
.task-details :deep(.el-select) {
  width: 100%;
}

.detail-row {
  margin-bottom: 12px;
  width: 100%;
}
</style>
