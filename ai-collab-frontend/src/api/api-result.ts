import axios, { type AxiosResponse } from 'axios'
import type { ApiResponse, ApiResult } from './types'

const SENSITIVE_KEY = /token|authorization|cookie|password|secret/i

const HTTP_STATUS_MESSAGES: Record<number, string> = {
  400: '请求参数错误',
  401: '登录状态已失效，请重新登录。',
  403: '没有权限执行此操作',
  404: '请求的资源不存在',
  409: '请求与当前状态冲突',
  410: '请求的资源已失效',
  429: '请求过于频繁',
  500: '服务器内部错误',
  502: '上游服务不可用',
  503: '服务暂时不可用',
  504: '服务响应超时',
}

const ERROR_CODE_MESSAGES: Record<string, string> = {
  REGISTRATION_DISABLED: '当前未开放公开注册',
  USERNAME_ALREADY_EXISTS: '该用户名已被使用',
  EMAIL_ALREADY_EXISTS: '该邮箱已被使用',
  CURRENT_PASSWORD_INVALID: '当前密码不正确',
  NEW_PASSWORD_SAME_AS_CURRENT: '新密码不能与当前密码相同',
  PROJECT_NOT_FOUND: '项目不存在或你无权访问',
  PROJECT_ADMIN_REQUIRED: '此操作需要项目管理员权限',
  PROJECT_OWNER_REQUIRED: '此操作仅限项目所有者',
  PROJECT_OWNER_CANNOT_BE_REMOVED: '不能修改或移除项目所有者',
  MEMBER_ALREADY_EXISTS: '该用户名或邮箱已被使用',
  MEMBER_NOT_FOUND: '项目成员不存在',
  INVITATION_INVALID: '邀请无效',
  INVITATION_EXPIRED: '邀请已过期',
  INVITATION_ALREADY_USED: '邀请已被使用',
  INVITATION_EMAIL_MISMATCH: '当前账号与邀请邮箱不匹配',
  MILESTONE_NOT_FOUND: '里程碑不存在',
  TASK_NOT_FOUND: '任务不存在',
  TASK_ASSIGNEE_NOT_MEMBER: '负责人必须是当前项目成员',
  TASK_MILESTONE_CROSS_PROJECT: '里程碑必须属于当前项目',
  TASK_INVALID_STATUS_TRANSITION: '不允许进行该任务状态变更。',
  TASK_BLOCKED_BY_DEPENDENCY: '该任务还有未完成的前置任务，暂时不能进入进行中或已完成。',
  TASK_DEPENDENCY_CYCLE: '任务依赖不能形成循环。',
  TASK_DEPENDENCY_CROSS_PROJECT: '不能依赖其他项目中的任务',
  COMMENT_NOT_FOUND: '评论不存在',
  COMMENT_AUTHOR_REQUIRED: '只能修改自己的评论',
  VERSION_CONFLICT: '数据已被其他操作修改，请刷新后重试。',
  AUTH_INVALID_CREDENTIALS: '用户名或密码错误',
  AUTH_UNAUTHORIZED: '登录状态已失效，请重新登录。',
  AUTH_FORBIDDEN: '当前请求来源不受信任',
  USER_DISABLED: '账号已被禁用',
  USER_NOT_FOUND: '用户不存在',
  VALIDATION_ERROR: '请检查填写内容',
  INTERNAL_ERROR: '服务暂时异常，请稍后重试',
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function nonBlankString(value: unknown): string | null {
  return typeof value === 'string' && value.trim() ? value : null
}

export function sanitizeApiData(value: unknown): unknown {
  if (Array.isArray(value)) {
    return value.map(sanitizeApiData)
  }
  if (!isRecord(value)) {
    return value
  }
  return Object.fromEntries(
    Object.entries(value)
      .filter(([key]) => !SENSITIVE_KEY.test(key))
      .map(([key, nested]) => [key, sanitizeApiData(nested)]),
  )
}

export function apiResultFromResponse<T>(response: AxiosResponse<ApiResponse<T>>): ApiResult<T> {
  return {
    httpStatus: response.status,
    code: response.data.code,
    message: response.data.message,
    data: response.data.data,
  }
}

export function toSafeApiResult(result: ApiResult<unknown>): ApiResult<unknown> {
  return {
    httpStatus: result.httpStatus,
    code: result.code,
    message: result.message,
    data: sanitizeApiData(result.data),
  }
}

export function normalizeApiError(error: unknown): ApiResult<unknown> {
  if (!axios.isAxiosError(error)) {
    return {
      httpStatus: null,
      code: 'UNKNOWN_ERROR',
      message: '发生未知错误，请稍后重试',
      data: null,
    }
  }

  const httpStatus = error.response?.status ?? null
  if (error.response) {
    const body = isRecord(error.response.data) ? error.response.data : {}
    const code = nonBlankString(body.code) ?? `HTTP_${error.response.status}`
    const backendMessage = nonBlankString(body.message)
    const statusMessage = HTTP_STATUS_MESSAGES[error.response.status]
      ?? '请求失败，请稍后重试'
    return {
      httpStatus,
      code,
      message: ERROR_CODE_MESSAGES[code] ?? backendMessage ?? statusMessage,
      data: sanitizeApiData(body.data ?? null),
    }
  }

  return {
    httpStatus: null,
    code: 'NETWORK_ERROR',
    message: '网络连接失败，请确认前后端服务是否正常运行。',
    data: null,
  }
}

export function formatSafeJson(value: unknown): string {
  try {
    return JSON.stringify(sanitizeApiData(value), null, 2) ?? 'null'
  } catch {
    return 'null'
  }
}
