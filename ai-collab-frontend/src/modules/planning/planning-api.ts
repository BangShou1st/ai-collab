import { httpClient } from '../../api/http-client'
import type { ApiResponse } from '../../api/types'
import type { TaskPlanDetailView, TaskPlanDraft, TaskPlan, TaskPlanEvent } from './types'

const base = (projectId: string) => `/projects/${projectId}/ai/task-plans`

export const planningApi = {
  list: (projectId: string, status = '') =>
    httpClient.get<ApiResponse<TaskPlan[]>>(base(projectId), { params: { status: status || undefined } }),
  create: (projectId: string, body: object) =>
    httpClient.post<ApiResponse<TaskPlan>>(base(projectId), body),
  /** C12: Returns full typed detail view */
  detail: (projectId: string, planId: string) =>
    httpClient.get<ApiResponse<TaskPlanDetailView>>(`${base(projectId)}/${planId}`),
  versions: (projectId: string, planId: string) =>
    httpClient.get<ApiResponse<Array<{ id: string; versionNo: number; sourceType: string; basedOnVersionId: string | null; createdBy: string; createdAt: string }>>>(`${base(projectId)}/${planId}/versions`),
  version: (projectId: string, planId: string, versionId: string) =>
    httpClient.get<ApiResponse<{ version: { id: string; versionNo: number; sourceType: string }; draft: TaskPlanDraft }>>(`${base(projectId)}/${planId}/versions/${versionId}`),
  action: (projectId: string, planId: string, action: 'cancel' | 'retry-detail' | 'regenerate') =>
    httpClient.post(`${base(projectId)}/${planId}/${action}`),
  save: (projectId: string, planId: string, baseVersionId: string, draft: TaskPlanDraft) =>
    httpClient.post(`${base(projectId)}/${planId}/versions`, { baseVersionId, draft }),
  restore: (projectId: string, planId: string, versionId: string) =>
    httpClient.post(`${base(projectId)}/${planId}/versions/${versionId}/restore`),
  confirm: (projectId: string, planId: string, versionId: string, key: string) =>
    httpClient.post(`${base(projectId)}/${planId}/confirm`, { versionId }, { headers: { 'Idempotency-Key': key } }),
  remove: (projectId: string, planId: string) => httpClient.delete(`${base(projectId)}/${planId}`),
  events: (projectId: string, planId: string) =>
    httpClient.get<ApiResponse<TaskPlanEvent[]>>(`${base(projectId)}/${planId}/events`),
  partialRegenerate: (projectId: string, planId: string, body: object) =>
    httpClient.post(`${base(projectId)}/${planId}/partial-regenerate`, body),
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
