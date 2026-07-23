import axios, { type AxiosResponse } from 'axios'
import type { ApiResponse, ApiResult } from './types'

const SENSITIVE_KEY = /token|authorization|cookie|password|secret/i

const HTTP_STATUS_MESSAGES: Record<number, string> = {
  400: '请求参数错误',
  401: '未登录或会话已失效',
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
    const backendMessage = nonBlankString(body.message)
    const statusMessage = HTTP_STATUS_MESSAGES[error.response.status]
      ?? nonBlankString(error.response.statusText)
      ?? `HTTP ${error.response.status} 请求失败`
    return {
      httpStatus,
      code: nonBlankString(body.code) ?? `HTTP_${error.response.status}`,
      message: backendMessage ?? statusMessage,
      data: sanitizeApiData(body.data ?? null),
    }
  }

  return {
    httpStatus: null,
    code: 'NETWORK_ERROR',
    message: '网络连接失败，请检查网络后重试',
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
