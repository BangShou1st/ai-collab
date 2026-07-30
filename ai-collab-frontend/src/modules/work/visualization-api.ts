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
}

export interface MemberLoadView {
  members: MemberLoad[]
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
