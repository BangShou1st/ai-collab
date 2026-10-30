import { describe, expect, it } from 'vitest'
import { resolveBackendOrigin } from './backend-origin'

const STAGING_ORIGIN = 'https://8443-agqyc3kteqehqgazwefpmhwulu.instabox.tech'

describe('resolveBackendOrigin', () => {
  it('生产分支缺少 BACKEND_ORIGIN 时明确失败', () => {
    const result = resolveBackendOrigin({ CF_PAGES_BRANCH: 'main' })
    expect(result.ok).toBe(false)
    expect(result.errorCode).toBe('BACKEND_ORIGIN_MISSING')
  })

  it('完全缺少分支信息（本地 wrangler dev）时同样失败', () => {
    const result = resolveBackendOrigin({})
    expect(result.ok).toBe(false)
    expect(result.errorCode).toBe('BACKEND_ORIGIN_MISSING')
  })

  it('预览分支允许回退到 Staging 网关', () => {
    const result = resolveBackendOrigin({ CF_PAGES_BRANCH: 'feature-demo' })
    expect(result.ok).toBe(true)
    expect(result.origin).toBe(STAGING_ORIGIN)
  })

  it('生产分支可通过 CF_PAGES_PRODUCTION_BRANCH 自定义', () => {
    const result = resolveBackendOrigin({
      CF_PAGES_BRANCH: 'release',
      CF_PAGES_PRODUCTION_BRANCH: 'release',
    })
    expect(result.ok).toBe(false)
    expect(result.errorCode).toBe('BACKEND_ORIGIN_MISSING')
    const preview = resolveBackendOrigin({
      CF_PAGES_BRANCH: 'main',
      CF_PAGES_PRODUCTION_BRANCH: 'release',
    })
    expect(preview.ok).toBe(true)
    expect(preview.origin).toBe(STAGING_ORIGIN)
  })

  it('合法 HTTPS 源通过并去除尾部斜杠', () => {
    const result = resolveBackendOrigin({ BACKEND_ORIGIN: 'https://api.example.com/' })
    expect(result.ok).toBe(true)
    expect(result.origin).toBe('https://api.example.com')
  })

  it('允许本地 HTTP 源用于本地联调', () => {
    const result = resolveBackendOrigin({ BACKEND_ORIGIN: 'http://localhost:8080' })
    expect(result.ok).toBe(true)
    expect(result.origin).toBe('http://localhost:8080')
  })

  it('非本地 HTTP 源判定为不安全', () => {
    const result = resolveBackendOrigin({ BACKEND_ORIGIN: 'http://api.example.com' })
    expect(result.ok).toBe(false)
    expect(result.errorCode).toBe('BACKEND_ORIGIN_INSECURE')
  })

  it('拒绝携带路径或查询参数的配置', () => {
    expect(resolveBackendOrigin({ BACKEND_ORIGIN: 'https://api.example.com/api' }).errorCode)
      .toBe('BACKEND_ORIGIN_INVALID')
    expect(resolveBackendOrigin({ BACKEND_ORIGIN: 'https://api.example.com?x=1' }).errorCode)
      .toBe('BACKEND_ORIGIN_INVALID')
  })

  it('拒绝非法 URL 与非 http 协议', () => {
    expect(resolveBackendOrigin({ BACKEND_ORIGIN: 'not-a-url' }).errorCode)
      .toBe('BACKEND_ORIGIN_INVALID')
    expect(resolveBackendOrigin({ BACKEND_ORIGIN: 'ftp://api.example.com' }).errorCode)
      .toBe('BACKEND_ORIGIN_INVALID')
  })
})
