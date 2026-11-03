<script setup lang="ts">
import { computed } from 'vue'
import {
  FolderAdd,
  User,
  DocumentAdd,
  Document,
  Cpu,
  CircleCheckFilled,
  ArrowRight,
  Close,
  Guide,
} from '@element-plus/icons-vue'
import type { HomeProject, HomeTask, HomeData } from '../../../api/home-api'

const props = defineProps<{
  projects: HomeProject[]
  tasks: HomeTask[]
  aiConfig: HomeData['aiConfigSummary']
  collapsed?: boolean
}>()

const emit = defineEmits<{
  (e: 'toggle'): void
  (e: 'dismiss'): void
}>()

const hasProjects = computed(() => props.projects.length > 0)
const hasTasks = computed(() => props.tasks.length > 0)
const isAiConfigured = computed(() => props.aiConfig.configured)

/** 「创建首个项目」必须以用户真正拥有（OWNER）的项目为准，仅是成员身份不算。 */
const hasOwnedProject = computed(() => props.projects.some((project) => project.role === 'OWNER'))

/** 跨项目入口的唯一可信目标：仅当用户恰好只有一个项目时才直达，否则进入项目列表让用户自己选择。 */
function crossProjectTarget(projectPathSuffix: string): string {
  if (props.projects.length === 1) {
    return `/projects/${props.projects[0]!.id}/${projectPathSuffix}`
  }
  return '/projects'
}

/** 真实可客观判定的核心就绪项（创建项目、配置 AI） */
const completedCoreCount = computed(() => {
  let count = 0
  if (hasOwnedProject.value) count += 1
  if (isAiConfigured.value) count += 1
  return count
})

const progressPercent = computed(() => {
  return Math.round((completedCoreCount.value / 2) * 100)
})

interface GuideStep {
  id: string
  num: number
  title: string
  desc: string
  icon: unknown
  status: 'completed' | 'todo' | 'optional' | 'disabled'
  statusText: string
  actionLabel: string
  actionTo: string | { path: string; query?: Record<string, string> }
  disabled?: boolean
}

const steps = computed<GuideStep[]>(() => [
  {
    id: 'project',
    num: 1,
    title: '创建首个项目',
    desc: '设立团队项目空间，统筹任务看板、里程碑与甘特推进。',
    icon: FolderAdd,
    status: hasOwnedProject.value ? 'completed' : 'todo',
    statusText: hasOwnedProject.value ? '已完成' : '待开始',
    actionLabel: hasOwnedProject.value ? '查看项目' : '立即创建',
    actionTo: '/projects',
  },
  {
    id: 'member',
    num: 2,
    title: '邀请团队成员',
    desc: hasProjects.value
      ? (props.projects.length > 1 ? '你参与了多个项目，请先选择要管理的项目，再邀请成员加入。' : '邀请同学或组员加入协作，无需等待全员到齐即可随时继续推进。')
      : '邀请同学或组员加入协作，无需等待全员到齐即可随时继续推进。',
    icon: User,
    status: hasProjects.value ? 'optional' : 'disabled',
    statusText: hasProjects.value ? '协同就绪' : '需先有项目',
    actionLabel: hasProjects.value ? (props.projects.length > 1 ? '选择项目' : '管理成员') : '待开启',
    actionTo: hasProjects.value ? crossProjectTarget('members') : '/projects',
    disabled: !hasProjects.value,
  },
  {
    id: 'task',
    num: 3,
    title: '体验任务看板',
    desc: hasProjects.value
      ? (props.projects.length > 1 ? '你参与了多个项目，请先选择项目，在看板或列表中细分子任务。' : '在看板或列表中细分子任务，推进团队进度或指派任务给组员。')
      : '在看板或列表中细分子任务，推进团队进度或指派任务给组员。',
    icon: DocumentAdd,
    status: !hasProjects.value
      ? 'disabled'
      : hasTasks.value
        ? 'completed'
        : 'optional',
    statusText: !hasProjects.value
      ? '需先有项目'
      : hasTasks.value
        ? '进行中'
        : '看板协同',
    actionLabel: !hasProjects.value
      ? '待开启'
      : props.projects.length > 1
        ? '选择项目'
        : hasTasks.value
          ? '进入看板'
          : '体验任务看板',
    actionTo: hasProjects.value ? crossProjectTarget('board') : '/projects',
    disabled: !hasProjects.value,
  },
  {
    id: 'document',
    num: 4,
    title: '沉淀项目知识',
    desc: hasProjects.value
      ? (props.projects.length > 1 ? '你参与了多个项目，请先选择项目，再上传需求文档或技术规范。' : '上传需求文档或技术规范，构建向量知识库以支持团队检索与问答。')
      : '上传需求文档或技术规范，构建向量知识库以支持团队检索与问答。',
    icon: Document,
    status: hasProjects.value ? 'optional' : 'disabled',
    statusText: hasProjects.value ? '随时探索' : '需先有项目',
    actionLabel: hasProjects.value ? (props.projects.length > 1 ? '选择项目' : '管理文档') : '待开启',
    actionTo: hasProjects.value ? crossProjectTarget('documents') : '/projects',
    disabled: !hasProjects.value,
  },
  {
    id: 'ai',
    num: 5,
    title: '启用 AI 辅助',
    desc: '连接大模型配置，按需体验 AI 任务拆解规划与 Agent 协作（非必选）。',
    icon: Cpu,
    status: isAiConfigured.value ? 'completed' : 'todo',
    statusText: isAiConfigured.value ? '已配置' : '待配置',
    actionLabel: isAiConfigured.value ? '管理 AI 设置' : '立即配置',
    actionTo: '/settings/ai',
  },
])
</script>

<template>
  <section class="onboarding-card" aria-label="新用户引导">
    <div class="onboarding-header">
      <div class="header-left">
        <div class="title-row">
          <span class="sparkle-badge" aria-hidden="true">
            <el-icon><Guide /></el-icon>
          </span>
          <h2 class="onboarding-title">开始你的协作之旅</h2>
          <span class="progress-pill">核心就绪 {{ completedCoreCount }}/2 项</span>
        </div>
        <p class="onboarding-subtitle">
          从创建项目开始，让团队与 AI 一起推进任务。按需开启功能，无需按部就班。
        </p>
      </div>
      <div class="header-actions">
        <button
          type="button"
          class="dismiss-btn"
          title="收起此引导"
          aria-label="收起新手引导"
          @click="emit('dismiss')"
        >
          <span class="dismiss-text">收起引导</span>
          <el-icon><Close /></el-icon>
        </button>
      </div>
    </div>

    <!-- 进度指示条 -->
    <div class="progress-track" role="progressbar" :aria-valuenow="progressPercent" aria-valuemin="0" aria-valuemax="100">
      <div class="progress-fill" :style="{ width: `${progressPercent}%` }"></div>
    </div>

    <!-- 步骤列表 -->
    <div class="steps-grid">
      <div
        v-for="step in steps"
        :key="step.id"
        class="step-item"
        :class="[`step-${step.status}`, { 'is-disabled': step.disabled }]"
      >
        <div class="step-icon-wrap">
          <el-icon :size="18"><component :is="step.icon" /></el-icon>
          <span v-if="step.status === 'completed'" class="done-mark" aria-hidden="true">
            <el-icon :size="11"><CircleCheckFilled /></el-icon>
          </span>
        </div>
        <div class="step-content">
          <div class="step-meta-row">
            <span class="step-num">0{{ step.num }}</span>
            <span class="step-tag" :class="`tag-${step.status}`">{{ step.statusText }}</span>
          </div>
          <strong class="step-title">{{ step.title }}</strong>
          <p class="step-desc">{{ step.desc }}</p>
          <div class="step-action-row">
            <router-link
              v-if="!step.disabled"
              :to="step.actionTo"
              class="step-link-btn"
              :class="{ 'is-primary': step.status === 'todo' }"
            >
              <span>{{ step.actionLabel }}</span>
              <el-icon :size="12"><ArrowRight /></el-icon>
            </router-link>
            <span v-else class="step-disabled-text">
              {{ step.actionLabel }}
            </span>
          </div>
        </div>
      </div>
    </div>
  </section>
</template>

<style scoped>
.onboarding-card {
  position: relative;
  background: linear-gradient(135deg, rgba(255, 255, 255, 0.98) 0%, rgba(246, 248, 255, 0.95) 100%);
  border: 1px solid rgba(88, 101, 216, 0.22);
  border-radius: var(--radius-card, 12px);
  padding: 18px 20px 20px;
  box-shadow:
    0 4px 20px -2px rgba(88, 101, 216, 0.08),
    0 1px 3px rgba(24, 32, 51, 0.04);
  overflow: hidden;
  transition: all 0.25s ease;
}

.onboarding-card::before {
  content: '';
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
  height: 3px;
  background: linear-gradient(90deg, #5865d8 0%, #38bdf8 100%);
}

.onboarding-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 14px;
}

.header-left {
  display: flex;
  flex-direction: column;
  gap: 4px;
  min-width: 0;
}

.title-row {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 10px;
}

.sparkle-badge {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  border-radius: 8px;
  background: linear-gradient(135deg, #5865d8 0%, #3b82f6 100%);
  color: #ffffff;
  box-shadow: 0 2px 6px rgba(88, 101, 216, 0.3);
}

.onboarding-title {
  font-size: 16px;
  font-weight: 700;
  color: #182033;
  margin: 0;
  letter-spacing: -0.01em;
}

.progress-pill {
  font-size: 11px;
  font-weight: 700;
  color: #5865d8;
  background: #eef0ff;
  border: 1px solid rgba(88, 101, 216, 0.2);
  padding: 2px 8px;
  border-radius: 999px;
}

.onboarding-subtitle {
  font-size: 13px;
  color: var(--color-text-secondary, #596279);
  margin: 0;
  line-height: 1.45;
}

.dismiss-btn {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  background: transparent;
  border: 1px solid transparent;
  border-radius: 6px;
  color: var(--color-text-muted, #7c8ba1);
  font-size: 12px;
  padding: 4px 8px;
  cursor: pointer;
  transition: all 0.2s ease;
}

.dismiss-btn:hover {
  background: rgba(24, 32, 51, 0.05);
  color: var(--color-text-primary, #182033);
}

/* 进度条 */
.progress-track {
  width: 100%;
  height: 4px;
  background: rgba(88, 101, 216, 0.12);
  border-radius: 999px;
  margin-bottom: 16px;
  overflow: hidden;
}

.progress-fill {
  height: 100%;
  background: linear-gradient(90deg, #5865d8 0%, #38bdf8 100%);
  border-radius: 999px;
  transition: width 0.35s ease;
}

/* 步骤网格 */
.steps-grid {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: 12px;
}

.step-item {
  display: flex;
  gap: 10px;
  background: #ffffff;
  border: 1px solid rgba(24, 32, 51, 0.08);
  border-radius: 8px;
  padding: 12px;
  transition: all 0.2s ease;
}

.step-item:hover:not(.is-disabled) {
  border-color: rgba(88, 101, 216, 0.35);
  box-shadow: 0 4px 12px rgba(88, 101, 216, 0.06);
  transform: translateY(-1px);
}

.step-item.step-completed {
  background: #fbfdff;
  border-color: rgba(16, 185, 129, 0.25);
}

.step-item.is-disabled {
  opacity: 0.65;
  background: #f8fafc;
}

.step-icon-wrap {
  position: relative;
  width: 32px;
  height: 32px;
  border-radius: 8px;
  background: #f1f3f9;
  color: #596279;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.step-completed .step-icon-wrap {
  background: #e6f7ef;
  color: #10b981;
}

.step-todo .step-icon-wrap {
  background: #eef0ff;
  color: #5865d8;
}

.done-mark {
  position: absolute;
  right: -3px;
  bottom: -3px;
  color: #10b981;
  background: #ffffff;
  border-radius: 50%;
  display: flex;
}

.step-content {
  display: flex;
  flex-direction: column;
  flex: 1;
  min-width: 0;
}

.step-meta-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 6px;
  margin-bottom: 3px;
}

.step-num {
  font-size: 11px;
  font-weight: 700;
  color: var(--color-text-muted, #7c8ba1);
}

.step-title {
  font-size: 13px;
  font-weight: 700;
  color: var(--color-text-primary, #182033);
  margin-bottom: 4px;
  line-height: 1.35;
}

.step-tag {
  font-size: 10px;
  font-weight: 600;
  padding: 1px 5px;
  border-radius: 4px;
  white-space: nowrap;
}

.tag-completed {
  background: #e6f7ef;
  color: #059669;
}

.tag-todo {
  background: #eef0ff;
  color: #5865d8;
}

.tag-optional {
  background: #f3f4f6;
  color: #4b5563;
}

.tag-disabled {
  background: #f3f4f6;
  color: #9ca3af;
}

.step-desc {
  font-size: 11.5px;
  color: var(--color-text-secondary, #596279);
  line-height: 1.4;
  margin: 0 0 8px;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.step-action-row {
  margin-top: auto;
}

.step-link-btn {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  font-size: 12px;
  font-weight: 600;
  color: #5865d8;
  text-decoration: none;
  transition: all 0.15s ease;
}

.step-link-btn:hover {
  color: #4351c5;
  text-decoration: underline;
}

.step-link-btn.is-primary {
  color: #3b82f6;
  font-weight: 700;
}

.step-disabled-text {
  font-size: 11.5px;
  color: #9ca3af;
}

@media (max-width: 1200px) {
  .steps-grid {
    grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
  }
}

@media (max-width: 768px) {
  .steps-grid {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 600px) {
  .onboarding-header {
    flex-direction: column;
    align-items: stretch;
  }
  .header-actions {
    align-self: flex-end;
  }
}
</style>
