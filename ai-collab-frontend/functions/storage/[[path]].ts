/**
 * Cloudflare Pages Function: 同源反向代理 /storage/* -> 后端存储代理
 * 部署至 Cloudflare Pages 时自动生效，转发预签名下载与二进制流。
 */
import { resolveBackendOrigin, type PagesBackendEnv } from '../api/_lib/backend-origin'

type Env = PagesBackendEnv

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
        code: 50302,
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
  const upstreamUrl = new URL(url.pathname + url.search, backendOrigin)

  const upstreamHeaders = new Headers()
  for (const [key, value] of request.headers.entries()) {
    const lowerKey = key.toLowerCase()
    if (!HOP_BY_HOP_HEADERS.has(lowerKey) && lowerKey !== 'host') {
      upstreamHeaders.set(key, value)
    }
  }

  upstreamHeaders.set('Host', upstreamUrl.host)
  upstreamHeaders.set('X-Forwarded-Host', url.host)
  upstreamHeaders.set('X-Forwarded-Proto', url.protocol.replace(':', ''))
  const clientIp = request.headers.get('cf-connecting-ip') || request.headers.get('x-real-ip')
  if (clientIp) {
    upstreamHeaders.set('X-Real-IP', clientIp)
    upstreamHeaders.set('X-Forwarded-For', clientIp)
  }

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

    const responseHeaders = new Headers()
    for (const [key, value] of upstreamResponse.headers.entries()) {
      const lower = key.toLowerCase()
      if (!HOP_BY_HOP_HEADERS.has(lower)) {
        responseHeaders.append(key, value)
      }
    }

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
        message: `Upstream storage unreachable: ${message}`,
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
