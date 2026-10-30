/**
 * Cloudflare Pages Function: 同源反向代理 /api/* -> InstaBox Staging 后端
 * 部署至 Cloudflare Pages 时自动生效，解决跨站点 Strict Cookie 与同源路径约束。
 */
import { resolveBackendOrigin, type PagesBackendEnv } from './_lib/backend-origin'

interface Env extends PagesBackendEnv {
  BACKEND_ORIGIN?: string
}

// 跳过逐跳（hop-by-hop）头
const HOP_BY_HOP_HEADERS = new Set([
  'connection',
  'keep-alive',
  'proxy-authenticate',
  'proxy-authorization',
  'te',
  'trailers',
  'transfer-encoding',
  'upgrade',
])

export const onRequest: PagesFunction<Env> = async (context) => {
  const { request, env } = context
  const url = new URL(request.url)

  // 生产分支缺少 BACKEND_ORIGIN 时明确失败；仅预览分支回退 Staging（见 _lib/backend-origin.ts）
  const resolution = resolveBackendOrigin(env)
  if (!resolution.ok || !resolution.origin) {
    return new Response(
      JSON.stringify({
        code: 50301,
        message: resolution.errorMessage ?? 'BACKEND_ORIGIN 配置无效',
        data: null,
      }),
      {
        status: 503,
        headers: {
          'Content-Type': 'application/json; charset=utf-8',
          'Cache-Control': 'no-store',
        },
      },
    )
  }
  const backendOrigin = resolution.origin
  
  // 构造上游 URL（保留完整路径与查询参数）
  const upstreamUrl = new URL(url.pathname + url.search, backendOrigin)

  // 构造上游请求头
  const upstreamHeaders = new Headers()
  for (const [key, value] of request.headers.entries()) {
    const lowerKey = key.toLowerCase()
    if (!HOP_BY_HOP_HEADERS.has(lowerKey) && lowerKey !== 'host') {
      upstreamHeaders.set(key, value)
    }
  }

  // 注入受信来源与主机头
  upstreamHeaders.set('Host', upstreamUrl.host)
  upstreamHeaders.set('X-Forwarded-Host', url.host)
  upstreamHeaders.set('X-Forwarded-Proto', url.protocol.replace(':', ''))
  const clientIp = request.headers.get('cf-connecting-ip') || request.headers.get('x-real-ip')
  if (clientIp) {
    upstreamHeaders.set('X-Real-IP', clientIp)
    upstreamHeaders.set('X-Forwarded-For', clientIp)
  }

  // 针对 OPTIONS 预检请求直接放行或转发
  const fetchOptions: RequestInit = {
    method: request.method,
    headers: upstreamHeaders,
    redirect: 'manual',
  }

  if (request.method !== 'GET' && request.method !== 'HEAD' && request.body) {
    fetchOptions.body = request.body
    // @ts-expect-error duplex is required by Cloudflare Workers when streaming request body
    fetchOptions.duplex = 'half'
  }

  try {
    const upstreamResponse = await fetch(upstreamUrl.toString(), fetchOptions)

    // 复制响应头，保持 Set-Cookie、Content-Type 与 SSE 流式
    const responseHeaders = new Headers()
    for (const [key, value] of upstreamResponse.headers.entries()) {
      const lower = key.toLowerCase()
      if (!HOP_BY_HOP_HEADERS.has(lower) && lower !== 'set-cookie') {
        responseHeaders.append(key, value)
      }
    }

    // 正确保留 Set-Cookie，优先使用 WinterCG / Workers 标准的 getSetCookie()
    if (typeof upstreamResponse.headers.getSetCookie === 'function') {
      for (const cookie of upstreamResponse.headers.getSetCookie()) {
        responseHeaders.append('set-cookie', cookie)
      }
    } else {
      const rawCookie = upstreamResponse.headers.get('set-cookie')
      if (rawCookie) {
        responseHeaders.append('set-cookie', rawCookie)
      }
    }

    // API 响应一律禁止 Cloudflare 边缘缓存，避免动态数据被缓存为静态内容
    responseHeaders.set('Cache-Control', 'no-store, no-cache, must-revalidate, max-age=0')
    responseHeaders.set('Pragma', 'no-cache')

    return new Response(upstreamResponse.body, {
      status: upstreamResponse.status,
      statusText: upstreamResponse.statusText,
      headers: responseHeaders,
    })
  } catch (err: unknown) {
    const message = err instanceof Error ? err.message : String(err)
    return new Response(
      JSON.stringify({
        code: 50201,
        message: `Upstream gateway unreachable: ${message}`,
        data: null,
      }),
      {
        status: 502,
        headers: {
          'Content-Type': 'application/json',
          'Cache-Control': 'no-store',
        },
      }
    )
  }
}
