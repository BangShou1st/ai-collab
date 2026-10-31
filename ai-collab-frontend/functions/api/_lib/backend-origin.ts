/**
 * Pages Function 后端源（BACKEND_ORIGIN）解析的纯函数实现。
 *
 * 环境语义约束：
 * - BACKEND_ORIGIN：Pages Function 转发的后端源，是部署级可信配置，不允许从请求头推断。
 * - 生产分支（CF_PAGES_BRANCH 等于生产分支，默认 main）缺少该配置时必须明确失败，
 *   禁止静默回退到 InstaBox Staging。
 * - 仅预览分支允许回退 Staging，保证 PR 预览环境开箱可用。
 */

export interface PagesBackendEnv {
  BACKEND_ORIGIN?: string
  CF_PAGES_BRANCH?: string
  CF_PAGES_PRODUCTION_BRANCH?: string
}

export type OriginErrorCode =
  | 'BACKEND_ORIGIN_MISSING'
  | 'BACKEND_ORIGIN_INVALID'
  | 'BACKEND_ORIGIN_INSECURE'

export interface OriginResolution {
  readonly ok: boolean
  readonly origin?: string
  readonly errorCode?: OriginErrorCode
  readonly errorMessage?: string
}

// 历史约定的 InstaBox Staging 网关，仅允许预览分支显式回退。
const STAGING_FALLBACK_ORIGIN = 'https://8443-agqyc3kteqehqgazwefpmhwulu.instabox.tech'
const LOCAL_HOSTNAMES = new Set(['localhost', '127.0.0.1', '[::1]', '::1'])

function invalid(errorCode: OriginErrorCode, errorMessage: string): OriginResolution {
  return { ok: false, errorCode, errorMessage }
}

export function resolveBackendOrigin(env: PagesBackendEnv): OriginResolution {
  const configured = env.BACKEND_ORIGIN?.trim()
  if (!configured) {
    const productionBranch = (env.CF_PAGES_PRODUCTION_BRANCH?.trim() || 'main')
    const branch = env.CF_PAGES_BRANCH?.trim() ?? ''
    if (branch && branch !== productionBranch) {
      return { ok: true, origin: STAGING_FALLBACK_ORIGIN }
    }
    return invalid(
      'BACKEND_ORIGIN_MISSING',
      'BACKEND_ORIGIN 环境变量未配置：生产环境禁止静默回退到 Staging 后端，'
        + '请在 Cloudflare Pages 项目设置中为生产环境显式配置 BACKEND_ORIGIN。',
    )
  }

  let parsed: URL
  try {
    parsed = new URL(configured)
  } catch {
    return invalid('BACKEND_ORIGIN_INVALID', 'BACKEND_ORIGIN 不是合法的 URL：' + configured)
  }
  if (parsed.protocol !== 'https:' && parsed.protocol !== 'http:') {
    return invalid('BACKEND_ORIGIN_INVALID', 'BACKEND_ORIGIN 只允许 http/https 协议：' + configured)
  }
  if ((parsed.pathname && parsed.pathname !== '/') || parsed.search || parsed.hash) {
    return invalid(
      'BACKEND_ORIGIN_INVALID',
      'BACKEND_ORIGIN 只允许源（origin），不得携带路径、查询参数或锚点：' + configured,
    )
  }
  if (parsed.protocol === 'http:' && !LOCAL_HOSTNAMES.has(parsed.hostname)) {
    return invalid(
      'BACKEND_ORIGIN_INSECURE',
      'BACKEND_ORIGIN 在非本地主机上必须使用 HTTPS：' + configured,
    )
  }
  return { ok: true, origin: configured.replace(/\/+$/, '') }
}
