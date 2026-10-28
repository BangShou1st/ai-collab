/**
 * Base URL / API Path 组合规则（前端镜像）：重复版本前缀识别、一键修正与端点预览。
 *
 * 与后端 `ProviderEndpointRules` 保持同一判定口径：只识别「Base URL 末段与
 * API Path 首段完全相同的版本段」（v1、v2、v1beta 等），不做任何通用路径
 * 去重——自定义网关的特殊路径与 Gemini /v1beta、Anthropic /v1 等合法协议
 * 路径不受影响。该检查纯本地执行（0 次请求），在发起网络请求前给出提示。
 */

const VERSION_SEGMENT = /^v\d+[a-z0-9]*$/

export interface EndpointPreview {
  /** 推理端点（Base + Path 直接拼接）。 */
  inferenceUrl: string
  /** 模型发现端点（同前缀 /models 或原生 models 段）。 */
  discoveryUrl: string
  /** 拼接处重复的版本段（如 v1）；无重复为 null。 */
  duplicateVersion: string | null
}

/** 模型发现端点推导：与后端 ProviderModelDiscoveryService.discoveryUrl 同口径。 */
export function discoveryUrlOf(baseUrl: string, apiPath: string): string {
  let base = baseUrl.trim()
  while (base.endsWith('/')) base = base.slice(0, -1)
  const path = apiPath.trim()
  const modelsSegment = /(^|\/)models(\/|:|$)/.exec(path)
  if (modelsSegment) {
    const end = modelsSegment.index + modelsSegment[0].length
    const trailing = /[:/]$/.test(modelsSegment[0]) ? 1 : 0
    return base + path.slice(0, end - trailing)
  }
  for (const suffix of ['/chat/completions', '/messages']) {
    if (path.endsWith(suffix)) return base + path.slice(0, path.length - suffix.length) + '/models'
  }
  return base + '/models'
}

function basePathSegments(baseUrl: string): string[] {
  try {
    const path = new URL(baseUrl).pathname
    return path.split('/').filter((segment) => segment.length > 0)
  } catch {
    return []
  }
}

/** 拼接处重复版本段；无重复返回 null。 */
export function duplicateVersionJunction(baseUrl: string, apiPath: string): string | null {
  const base = baseUrl.trim()
  const path = apiPath.trim()
  if (!base || !path.startsWith('/')) return null
  const segments = basePathSegments(base)
  if (!segments.length) return null
  const lastBaseSegment = segments[segments.length - 1]
  const firstPathSegment = path.slice(1).split('/', 2)[0]
  if (!firstPathSegment) return null
  if (VERSION_SEGMENT.test(lastBaseSegment) && lastBaseSegment === firstPathSegment) {
    return lastBaseSegment
  }
  return null
}

/** 推理端点预览：Base + Path 直接拼接（重复原样呈现，让错误可见）。 */
export function inferenceUrlPreview(baseUrl: string, apiPath: string): string {
  let base = baseUrl.trim()
  while (base.endsWith('/')) base = base.slice(0, -1)
  const path = apiPath.trim()
  if (!path) return base
  return base + (path.startsWith('/') ? path : `/${path}`)
}

export function previewEndpoint(baseUrl: string, apiPath: string): EndpointPreview {
  return {
    inferenceUrl: inferenceUrlPreview(baseUrl, apiPath),
    discoveryUrl: discoveryUrlOf(baseUrl, apiPath),
    duplicateVersion: duplicateVersionJunction(baseUrl, apiPath),
  }
}

export interface EndpointFixSuggestion {
  /** 修正方案 A：去掉 Base URL 末尾的重复版本段。 */
  fixedBaseUrl: string | null
  /** 修正方案 B：去掉 API Path 开头的重复版本段。 */
  fixedApiPath: string | null
}

/**
 * 一键修正建议：两个方向各给出一个（修 Base 或修 Path），由用户选择；
 * 无法解析时两方向均为 null。
 */
export function endpointFixSuggestions(baseUrl: string, apiPath: string): EndpointFixSuggestion {
  const duplicate = duplicateVersionJunction(baseUrl, apiPath)
  if (!duplicate) return { fixedBaseUrl: null, fixedApiPath: null }
  return {
    fixedBaseUrl: baseUrl.trim().replace(new RegExp(`/${duplicate}/?$`), '/'),
    fixedApiPath: apiPath.trim().slice(1 + duplicate.length),
  }
}
