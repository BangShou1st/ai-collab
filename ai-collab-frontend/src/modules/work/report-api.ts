import { httpClient } from '../../api/http-client'
import { apiResultFromResponse } from '../../api/api-result'
import type { ApiResponse, ApiResult } from '../../api/types'

export interface TaskStatistics {
  totalTasks: number
  completedTasks: number
  inProgressTasks: number
  overdueTasks: number
  completionRate: number
}

export interface MilestoneStatistics {
  totalMilestones: number
  completedMilestones: number
  upcomingMilestones: number
  overdueMilestones: number
}

export interface TopContributor {
  displayName: string
  completedTasks: number
  totalTasks: number
}

export interface WeeklyReport {
  reportDate: string
  taskStats: TaskStatistics
  milestoneStats: MilestoneStatistics
  topContributors: TopContributor[]
  highlights: string[]
  risks: string[]
}

export interface RiskItem {
  taskId: string | null
  taskTitle: string
  riskType: string
  severity: string
  description: string
  dueDate: string | null
  assigneeName: string | null
}

export interface RiskSummary {
  highRiskCount: number
  mediumRiskCount: number
  lowRiskCount: number
  overallAssessment: string
}

export interface RiskAnalysis {
  risks: RiskItem[]
  summary: RiskSummary
}

export interface PlanComparison {
  planId: string
  planName: string
  taskComparisons: TaskComparison[]
  summary: ComparisonSummary
}

export interface TaskComparison {
  plannedTaskId: string | null
  plannedTitle: string
  actualTaskId: string | null
  actualTitle: string
  status: string
  diffs: FieldDiff[]
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
  async weeklyReport(projectId: string): Promise<ApiResult<WeeklyReport>> {
    return apiResultFromResponse(
      await httpClient.get<ApiResponse<WeeklyReport>>(`/projects/${projectId}/reports/weekly`),
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
