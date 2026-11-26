/**
 * Staging 全链路业务与安全验收脚本（手动执行，不属于任何自动门禁）。
 *
 * 会对目标环境执行真实写操作：注册普通用户、登录、创建项目、上传文档。
 *
 * 用法:
 *   STAGING_BASE_URL=https://<staging-host> ALLOW_REMOTE_WRITE=1 node ai-collab-deploy/scripts/manual/staging-lifecycle.mjs
 *
 * 退出码: 0 全部通过；1 任一检查失败（关键步骤状态码不符合预期时立即中止）；2 参数/目标地址校验未通过。
 */
import { resolveTarget } from './remote-target.mjs'

const BASE_URL = resolveTarget('staging-lifecycle.mjs')
const ORIGIN = BASE_URL
const TEST_PASSWORD = 'Password123!'

const results = {}

class StepFailure extends Error {}

function record(key, label, ok, detail = '') {
  results[key] = ok ? 'PASS' : `FAIL${detail ? ` (${detail})` : ''}`
  console.log(`${label}: ${results[key]}`)
  return ok
}

async function readJson(res) {
  const text = await res.text()
  try {
    return JSON.parse(text)
  } catch {
    return { raw: text.slice(0, 200) }
  }
}

/** 关键步骤：状态码不在预期内时记录失败并立即中止后续步骤。 */
function requireStatus(key, label, res, expected, data) {
  if (!expected.includes(res.status)) {
    record(key, label, false, `HTTP ${res.status}，期望 ${expected.join('/')}: ${JSON.stringify(data)}`)
    throw new StepFailure(`${label} 返回 HTTP ${res.status}，期望 ${expected.join('/')}`)
  }
}

/** 关键步骤：业务字段不符合预期时同样立即中止。 */
function requireOk(key, label, ok, detail) {
  if (!record(key, label, ok, detail)) throw new StepFailure(`${label} 未通过`)
}

async function main() {
  console.log(`=== 开始 Staging 全链路验收: ${BASE_URL} ===\n`)

  // 1. 根页面 SPA 检查
  {
    const res = await fetch(`${BASE_URL}/`)
    const text = await res.text()
    requireStatus('1_ROOT_SPA_PAGE', '[1] 根页面 SPA', res, [200], text.slice(0, 200))
    requireOk('1_ROOT_SPA_PAGE', '[1] 根页面 SPA', text.includes('高校竞赛项目协作平台'), '页面缺少预期标题')
  }

  // 2. 注册策略检查
  {
    const res = await fetch(`${BASE_URL}/api/v1/auth/registration-policy`)
    const data = await readJson(res)
    requireStatus('2_REGISTRATION_POLICY', '[2] 注册策略公开开启', res, [200], data)
    requireOk('2_REGISTRATION_POLICY', '[2] 注册策略公开开启',
      data.code === 'SUCCESS' && data.data?.enabled === true, JSON.stringify(data))
  }

  // 3. 普通用户注册与权限检查（新用户不可成为管理员，Origin 校验，Cookie 属性）
  const testUser = `user_${Date.now()}`
  let userCookie = ''
  let userAccessToken = ''
  {
    const res = await fetch(`${BASE_URL}/api/v1/auth/register`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'Origin': ORIGIN },
      body: JSON.stringify({
        username: testUser,
        password: TEST_PASSWORD,
        displayName: 'Test Normal User',
        email: `${testUser}@example.org`,
      }),
    })
    const data = await readJson(res)
    requireStatus('3_REGISTER_NORMAL_USER_NON_ADMIN', '[3] 普通注册', res, [200], data)
    const setCookie = res.headers.get('set-cookie') || ''
    userCookie = setCookie.split(';')[0]
    userAccessToken = data.data?.accessToken || ''
    const isAdmin = data.data?.user?.systemAdmin === true
    requireOk('3_REGISTER_NORMAL_USER_NON_ADMIN', '[3] 普通注册用户非系统管理员',
      !isAdmin && data.data?.user?.username === testUser && !!userAccessToken && !!userCookie,
      JSON.stringify(data))
    const lower = setCookie.toLowerCase()
    record('3_COOKIE_SECURITY', '[3] 刷新 Cookie 安全标 (Secure, HttpOnly, SameSite=Strict)',
      lower.includes('samesite=strict') && lower.includes('secure') && lower.includes('httponly'), setCookie)
  }

  // 4. Token 刷新（带 Cookie 与 Origin）
  let refreshedToken = ''
  {
    const res = await fetch(`${BASE_URL}/api/v1/auth/refresh`, {
      method: 'POST',
      headers: { 'Cookie': userCookie, 'Origin': ORIGIN },
    })
    const data = await readJson(res)
    requireStatus('4_TOKEN_REFRESH', '[4] 令牌刷新 (POST /refresh)', res, [200], data)
    refreshedToken = data.data?.accessToken || ''
    requireOk('4_TOKEN_REFRESH', '[4] 令牌刷新 (POST /refresh)',
      data.code === 'SUCCESS' && !!refreshedToken, JSON.stringify(data))
  }

  // 5. 退出登录（带 Cookie 与 Origin）
  {
    const res = await fetch(`${BASE_URL}/api/v1/auth/logout`, {
      method: 'POST',
      headers: {
        'Cookie': userCookie,
        'Origin': ORIGIN,
        'Authorization': `Bearer ${refreshedToken || userAccessToken}`,
      },
    })
    const data = await readJson(res)
    requireStatus('5_LOGOUT_COOKIE_CLEARED', '[5] 退出登录', res, [200], data)
    const setCookie = res.headers.get('set-cookie') || ''
    record('5_LOGOUT_COOKIE_CLEARED', '[5] 退出登录且清除 Cookie',
      setCookie.includes('Max-Age=0') || setCookie.includes('expires='), setCookie)
  }

  // 6. 重新登录
  {
    const res = await fetch(`${BASE_URL}/api/v1/auth/login`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'Origin': ORIGIN },
      body: JSON.stringify({ username: testUser, password: TEST_PASSWORD }),
    })
    const data = await readJson(res)
    requireStatus('6_RE_LOGIN', '[6] 重新登录验证', res, [200], data)
    const setCookie = res.headers.get('set-cookie') || ''
    userCookie = setCookie.split(';')[0]
    userAccessToken = data.data?.accessToken || ''
    requireOk('6_RE_LOGIN', '[6] 重新登录验证', data.code === 'SUCCESS' && !!userAccessToken, JSON.stringify(data))
  }

  // 7. 项目创建
  let projectId = null
  {
    const res = await fetch(`${BASE_URL}/api/v1/projects`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Origin': ORIGIN,
        'Authorization': `Bearer ${userAccessToken}`,
      },
      body: JSON.stringify({
        name: `竞赛项目_${Date.now()}`,
        description: 'Staging 真实端到端集成测试项目',
        type: 'COMPETITION',
        startDate: '2026-11-01',
        dueDate: '2026-12-31',
      }),
    })
    const data = await readJson(res)
    requireStatus('7_PROJECT_CREATION', '[7] 业务创建项目 (POST /api/v1/projects)', res, [201], data)
    projectId = data.data?.id
    requireOk('7_PROJECT_CREATION', `[7] 业务创建项目 (ID: ${projectId})`,
      data.code === 'SUCCESS' && !!projectId, JSON.stringify(data))
  }

  // 8. 文档上传（multipart/form-data 至 MinIO）
  {
    const formData = new FormData()
    const blob = new Blob(['# 竞赛协作规范\n本文档上传用于验证 MinIO 对象存储与 Tika 解析。'], { type: 'text/markdown' })
    formData.append('file', blob, 'collaboration_spec.md')
    const res = await fetch(`${BASE_URL}/api/v1/projects/${projectId}/documents`, {
      method: 'POST',
      headers: { 'Origin': ORIGIN, 'Authorization': `Bearer ${userAccessToken}` },
      body: formData,
    })
    const data = await readJson(res)
    requireStatus('8_DOCUMENT_UPLOAD_MINIO', '[8] 文档上传至 MinIO (POST /documents)', res, [200, 202], data)
    const docId = data.data?.id
    requireOk('8_DOCUMENT_UPLOAD_MINIO', `[8] 文档上传至 MinIO (Doc ID: ${docId})`,
      data.code === 'SUCCESS' && !!docId, JSON.stringify(data))
  }

  // 9. SSE 端点连通性（虚构 runId，仅作信息输出，不计入通过/失败）
  {
    const controller = new AbortController()
    const fakeRunId = '00000000-0000-0000-0000-000000000001'
    const timeout = setTimeout(() => controller.abort(), 4000)
    try {
      const res = await fetch(`${BASE_URL}/api/v1/projects/${projectId}/agent/runs/${fakeRunId}/events`, {
        headers: { 'Accept': 'text/event-stream', 'Authorization': `Bearer ${userAccessToken}` },
        signal: controller.signal,
      })
      results['9_SSE_STREAMING'] = `INFO (status: ${res.status}, content-type: ${res.headers.get('content-type') || ''})`
    } catch (e) {
      results['9_SSE_STREAMING'] = e.name === 'AbortError' ? 'INFO (stream held open until timeout)' : `INFO (${e.message})`
    } finally {
      clearTimeout(timeout)
      controller.abort()
    }
    console.log(`[9] SSE 事件流连通 (仅信息): ${results['9_SSE_STREAMING']}`)
  }

  // 10. Actuator 隔离（外部应返回 404）
  {
    const res = await fetch(`${BASE_URL}/actuator/health`)
    await res.body?.cancel()
    record('10_ACTUATOR_EXTERNAL_404', '[10] Nginx 对外阻断 /actuator', res.status === 404,
      `Status is ${res.status}, should be 404`)
  }

  // 11. 跨站来源防伪造拦截（不合法的 Origin 必须返回 403）
  {
    const res = await fetch(`${BASE_URL}/api/v1/auth/register`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'Origin': 'https://evil-attacker.example.com' },
      body: JSON.stringify({
        username: `attacker_${Date.now()}`,
        password: TEST_PASSWORD,
        displayName: 'Attacker',
        email: 'attacker@evil.com',
      }),
    })
    await res.body?.cancel()
    record('11_FORBIDDEN_UNTRUSTED_ORIGIN', '[11] 跨站恶意来源校验拦截 (Evil Origin -> 403)', res.status === 403,
      `Status is ${res.status}, should be 403`)
  }
}

try {
  await main()
} catch (err) {
  console.error(`\n中止: ${err instanceof StepFailure ? err.message : err?.stack || err}`)
  process.exitCode = 1
} finally {
  console.log('\n=== 测试汇总 ===')
  console.log(JSON.stringify(results, null, 2))
  if (Object.values(results).some((v) => v.startsWith('FAIL'))) process.exitCode = 1
}
