import { httpClient } from '../../api/http-client'
import { apiResultFromResponse } from '../../api/api-result'
import type { ApiResponse, ApiResult } from '../../api/types'

export interface GanttTask {
  id: string
  title: string
  status: string
  assigneeName: string
  startDate: string | null
  dueDate: string | null
  sortOrder: number
}

export interface GanttMilestone {
  id: string
  name: string
  status: string
  targetDate: string | null
}

export interface GanttDependency {
  taskId: string
  dependsOnTaskId: string
}

export interface GanttView {
  tasks: GanttTask[]
  milestones: GanttMilestone[]
  dependencies: GanttDependency[]
}

export interface GraphNode {
  id: string
  title: string
  status: string
  assigneeName: string
}

export interface GraphEdge {
  source: string
  target: string
}

export interface DependencyGraphView {
  nodes: GraphNode[]
  edges: GraphEdge[]
}

export interface CalendarEvent {
  id: string
  title: string
  type: string
  date: string
  status: string
}

export interface CalendarView {
  events: CalendarEvent[]
}

export interface MemberLoad {
  userId: string
  displayName: string
  totalTasks: number
  completedTasks: number
  inProgressTasks: number
  overdueTasks: number
  totalEstimateHours: number
  completedHours: number
  /** 当前未完成任务数（TODO/IN_PROGRESS/BLOCKED，业务正确性专项新增） */
  uncompletedTasks?: number
  /** 未完成任务的估算工时合计（当前负担口径，NULL 估算不计入） */
  uncompletedEstimateHours?: number
  /** 已完成任务的累计估算工时（历史口径） */
  completedEstimateHours?: number
  /** 未完成且未填预计工时的任务数（估算缺失单独归类） */
  missingEstimateTasks?: number
}

export interface MemberLoadView {
  members: MemberLoad[]
  /** 未完成且未分配负责人的任务数（不归属任何成员，单独汇总） */
  unassignedUncompletedTasks?: number
}

export const visualizationApi = {
  async gantt(projectId: string): Promise<ApiResult<GanttView>> {
    return apiResultFromResponse(
      await httpClient.get<ApiResponse<GanttView>>(`/projects/${projectId}/visualization/gantt`),
    )
  },
  async dependencyGraph(projectId: string): Promise<ApiResult<DependencyGraphView>> {
    return apiResultFromResponse(
      await httpClient.get<ApiResponse<DependencyGraphView>>(`/projects/${projectId}/visualization/dependency-graph`),
    )
  },
  async calendar(projectId: string, year: number, month: number): Promise<ApiResult<CalendarView>> {
    return apiResultFromResponse(
      await httpClient.get<ApiResponse<CalendarView>>(
        `/projects/${projectId}/visualization/calendar`,
        { params: { year, month } },
      ),
    )
  },
  async memberLoad(projectId: string): Promise<ApiResult<MemberLoadView>> {
    return apiResultFromResponse(
      await httpClient.get<ApiResponse<MemberLoadView>>(`/projects/${projectId}/visualization/member-load`),
    )
  },
}
