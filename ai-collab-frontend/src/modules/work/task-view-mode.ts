export type TaskCenterViewType = 'board' | 'list' | 'gantt' | 'calendar' | 'dependency-graph'

interface TaskViewRoute {
  path?: string
  query?: Record<string, unknown>
  meta?: Record<string, unknown>
}

const VIEWS: TaskCenterViewType[] = ['board', 'list', 'gantt', 'calendar', 'dependency-graph']

export function taskViewFromRoute(route: TaskViewRoute): TaskCenterViewType {
  const queryView = route.query?.view
  if (typeof queryView === 'string' && VIEWS.includes(queryView as TaskCenterViewType)) {
    return queryView as TaskCenterViewType
  }
  const initialView = route.meta?.initialView
  if (typeof initialView === 'string' && VIEWS.includes(initialView as TaskCenterViewType)) {
    return initialView as TaskCenterViewType
  }
  const path = route.path ?? ''
  if (path.endsWith('/board')) return 'board'
  if (path.endsWith('/gantt')) return 'gantt'
  if (path.endsWith('/calendar')) return 'calendar'
  if (path.endsWith('/dependency-graph')) return 'dependency-graph'
  return 'board'
}
