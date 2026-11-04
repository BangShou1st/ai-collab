import { httpClient } from '../../api/http-client'
import { apiResultFromResponse } from '../../api/api-result'
import type { ApiResponse, ApiResult } from '../../api/types'

export interface TaskStatistics {
  totalTasks: number
  completedTasks: number
  inProgressTasks: number
  overdueTasks: number
  completionRate: number
  /** 以下为业务正确性专项新增（Agent A），可选以兼容旧响应 */
  canceledTasks?: number
  activeTasks?: number
  completedWithoutCompletedAtCount?: number
}

export interface MilestoneStatistics {
  totalMilestones: number
  completedMilestones: number
  upcomingMilestones: number
  overdueMilestones: number
  canceledMilestones?: number
  /** upcomingMilestones 的统计窗口天数（当前为 7 天） */
  dueSoonWindowDays?: number
}

export interface TopContributor {
  displayName: string
  completedTasks: number
  totalTasks: number
  completedInPeriod?: number
  createdInPeriod?: number
}

export interface WeeklyPeriodStats {
  createdInPeriod?: number
  completedInPeriod?: number
  milestoneCreatedInPeriod?: number
  milestoneCompletedInPeriod?: number
}

export interface WeeklyReport {
  reportDate: string
  /** 报告覆盖区间（业务正确性专项新增） */
  startDate?: string
  endDate?: string
  periodStats?: WeeklyPeriodStats
  dataSource?: string
  notes?: string[]
  taskStats: TaskStatistics
  milestoneStats: MilestoneStatistics
  topContributors: TopContributor[]
  highlights: string[]
  risks: string[]
}

export interface RiskRule {
  code: string
  name: string
  description: string
}

export interface RiskItem {
  taskId: string | null
  taskTitle: string
  riskType: string
  severity: string
  description: string
  dueDate: string | null
  assigneeName: string | null
  /** 命中的规则编码（规则检测，非 AI 预测） */
  rule?: string
  /** UNASSIGNED 聚合条目涉及的任务数 */
  affectedTaskCount?: number
}

export interface RiskSummary {
  highRiskCount: number
  mediumRiskCount: number
  lowRiskCount: number
  overallAssessment: string
  riskItemCount?: number
  affectedTaskCount?: number
  checkedTaskCount?: number
}

export interface RiskAnalysis {
  risks: RiskItem[]
  summary: RiskSummary
  rules?: RiskRule[]
  dataSource?: string
}

export interface PlanComparison {
  planId: string
  planName: string
  taskComparisons: TaskComparison[]
  summary: ComparisonSummary
  /** COMPARED | PLAN_NOT_APPLIED */
  comparisonStatus?: string
  baselineSource?: 'APPLIED_VERSION' | 'NONE' | string
  baselineVersionId?: string | null
  baselineVersionNo?: number | null
  appliedAt?: string | null
  message?: string
  unlinkedTaskCount?: number
  otherVersionTaskCount?: number
  ambiguousTaskCount?: number
}

export interface TaskComparison {
  plannedTaskId: string | null
  plannedTitle: string
  actualTaskId: string | null
  actualTitle: string
  status: string
  diffs: FieldDiff[]
  actualStatus?: string | null
  note?: string | null
}

export interface FieldDiff {
  fieldName: string
  plannedValue: string
  actualValue: string
}

export interface ComparisonSummary {
  totalPlanned: number
  matchedTasks: number
  modifiedTasks: number
  missingTasks: number
  extraTasks: number
}

export const reportApi = {
  async weeklyReport(projectId: string, startDate?: string, endDate?: string): Promise<ApiResult<WeeklyReport>> {
    return apiResultFromResponse(
      await httpClient.get<ApiResponse<WeeklyReport>>(`/projects/${projectId}/reports/weekly`, {
        params: startDate && endDate ? { startDate, endDate } : undefined,
      }),
    )
  },
  async riskAnalysis(projectId: string): Promise<ApiResult<RiskAnalysis>> {
    return apiResultFromResponse(
      await httpClient.get<ApiResponse<RiskAnalysis>>(`/projects/${projectId}/reports/risks`),
    )
  },
  async planComparison(projectId: string, planId: string): Promise<ApiResult<PlanComparison>> {
    return apiResultFromResponse(
      await httpClient.get<ApiResponse<PlanComparison>>(
        `/projects/${projectId}/reports/plan-comparison`,
        { params: { planId } },
      ),
    )
  },
}
