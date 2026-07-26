import { httpClient } from '../../api/http-client'
import type { ApiResponse } from '../../api/types'
import type { PlanPermissions, TaskPlan, TaskPlanDraft } from './types'

const base = (projectId: string) => `/projects/${projectId}/ai/task-plans`

export const planningApi = {
  list: (projectId: string, status = '') =>
    httpClient.get<ApiResponse<TaskPlan[]>>(base(projectId), { params: { status: status || undefined } }),
  create: (projectId: string, body: object) =>
    httpClient.post<ApiResponse<TaskPlan>>(base(projectId), body),
  detail: (projectId: string, planId: string) =>
    httpClient.get<ApiResponse<{ plan: TaskPlan; permissions: PlanPermissions }>>(`${base(projectId)}/${planId}`),
  versions: (projectId: string, planId: string) =>
    httpClient.get<ApiResponse<Array<{ id: string; versionNo: number; sourceType: string }>>>(`${base(projectId)}/${planId}/versions`),
  version: (projectId: string, planId: string, versionId: string) =>
    httpClient.get<ApiResponse<{ draft: TaskPlanDraft }>>(`${base(projectId)}/${planId}/versions/${versionId}`),
  action: (projectId: string, planId: string, action: 'cancel' | 'retry-detail' | 'regenerate') =>
    httpClient.post(`${base(projectId)}/${planId}/${action}`),
  save: (projectId: string, planId: string, baseVersionId: string, draft: TaskPlanDraft) =>
    httpClient.post(`${base(projectId)}/${planId}/versions`, { baseVersionId, draft }),
  restore: (projectId: string, planId: string, versionId: string) =>
    httpClient.post(`${base(projectId)}/${planId}/versions/${versionId}/restore`),
  confirm: (projectId: string, planId: string, versionId: string, key: string) =>
    httpClient.post(`${base(projectId)}/${planId}/confirm`, { versionId }, { headers: { 'Idempotency-Key': key } }),
  remove: (projectId: string, planId: string) => httpClient.delete(`${base(projectId)}/${planId}`),
}

export function confirmationKey(projectId: string, planId: string, versionId: string): string {
  const storageKey = `planning-confirm:${projectId}:${planId}:${versionId}`
  const existing = sessionStorage.getItem(storageKey)
  if (existing) return existing
  const key = crypto.randomUUID()
  sessionStorage.setItem(storageKey, key)
  return key
}

export function clearConfirmationKey(projectId: string, planId: string, versionId: string): void {
  sessionStorage.removeItem(`planning-confirm:${projectId}:${planId}:${versionId}`)
}
