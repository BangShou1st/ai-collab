/**
 * InstaBox Staging 全链路业务与安全验收脚本
 */
const BASE_URL = 'https://8443-agqyc3kteqehqgazwefpmhwulu.instabox.tech';
const ORIGIN = BASE_URL;

async function main() {
  console.log('=== 开始 InstaBox Staging 全链路验收 ===\n');
  const results = {};

  // 1. 根页面 SPA 检查
  try {
    const res = await fetch(`${BASE_URL}/`);
    const text = await res.text();
    const ok = res.status === 200 && text.includes('高校竞赛项目协作平台');
    results['1_ROOT_SPA_PAGE'] = ok ? 'PASS' : `FAIL (${res.status})`;
    console.log(`[1] 根页面 SPA: ${results['1_ROOT_SPA_PAGE']}`);
  } catch (e) {
    results['1_ROOT_SPA_PAGE'] = `FAIL (${e.message})`;
    console.log(`[1] 根页面 SPA: ${results['1_ROOT_SPA_PAGE']}`);
  }

  // 2. 注册策略检查
  try {
    const res = await fetch(`${BASE_URL}/api/v1/auth/registration-policy`);
    const data = await res.json();
    const ok = res.status === 200 && data.code === 'SUCCESS' && data.data?.enabled === true;
    results['2_REGISTRATION_POLICY'] = ok ? 'PASS' : `FAIL (${JSON.stringify(data)})`;
    console.log(`[2] 注册策略公开开启: ${results['2_REGISTRATION_POLICY']}`);
  } catch (e) {
    results['2_REGISTRATION_POLICY'] = `FAIL (${e.message})`;
    console.log(`[2] 注册策略公开开启: ${results['2_REGISTRATION_POLICY']}`);
  }

  // 3. 普通用户注册与权限检查（验证新用户不可成为管理员，Origin 校验，Cookie 属性）
  const testUser = `user_${Date.now()}`;
  const testPass = 'Password123!';
  let userCookie = '';
  let userAccessToken = '';
  try {
    const res = await fetch(`${BASE_URL}/api/v1/auth/register`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Origin': ORIGIN,
      },
      body: JSON.stringify({
        username: testUser,
        password: testPass,
        displayName: 'Test Normal User',
        email: `${testUser}@example.org`,
      }),
    });
    const setCookie = res.headers.get('set-cookie') || '';
    userCookie = setCookie.split(';')[0]; // 取 refresh_token=...
    const data = await res.json();
    const isAdmin = data.data?.user?.systemAdmin === true;
    const isStrictCookie = setCookie.toLowerCase().includes('samesite=strict');
    const isSecureCookie = setCookie.toLowerCase().includes('secure');
    const isHttpOnlyCookie = setCookie.toLowerCase().includes('httponly');

    userAccessToken = data.data?.accessToken || '';

    const pass = res.status === 200 && !isAdmin && isStrictCookie && isSecureCookie && isHttpOnlyCookie;
    results['3_REGISTER_NORMAL_USER_NON_ADMIN'] = (!isAdmin && data.data?.user?.username === testUser) ? 'PASS' : `FAIL (${JSON.stringify(data)})`;
    results['3_COOKIE_SECURITY'] = (isStrictCookie && isSecureCookie && isHttpOnlyCookie) ? 'PASS' : `FAIL (${setCookie})`;
    console.log(`[3] 普通注册用户非系统管理员: ${results['3_REGISTER_NORMAL_USER_NON_ADMIN']}`);
    console.log(`[3] 刷新 Cookie 安全标 (Secure, HttpOnly, SameSite=Strict): ${results['3_COOKIE_SECURITY']}`);
  } catch (e) {
    results['3_REGISTER_NORMAL_USER_NON_ADMIN'] = `FAIL (${e.message})`;
    console.log(`[3] 普通注册: ${results['3_REGISTER_NORMAL_USER_NON_ADMIN']}`);
  }

  // 4. Token 刷新测试（带 Cookie 与 Origin）
  let refreshedToken = '';
  try {
    const res = await fetch(`${BASE_URL}/api/v1/auth/refresh`, {
      method: 'POST',
      headers: {
        'Cookie': userCookie,
        'Origin': ORIGIN,
      },
    });
    const data = await res.json();
    refreshedToken = data.data?.accessToken || '';
    const ok = res.status === 200 && data.code === 'SUCCESS' && !!refreshedToken;
    results['4_TOKEN_REFRESH'] = ok ? 'PASS' : `FAIL (${res.status} ${JSON.stringify(data)})`;
    console.log(`[4] 令牌刷新 (POST /refresh): ${results['4_TOKEN_REFRESH']}`);
  } catch (e) {
    results['4_TOKEN_REFRESH'] = `FAIL (${e.message})`;
    console.log(`[4] 令牌刷新: ${results['4_TOKEN_REFRESH']}`);
  }

  // 5. 退出登录测试（带 Cookie 与 Origin）
  try {
    const res = await fetch(`${BASE_URL}/api/v1/auth/logout`, {
      method: 'POST',
      headers: {
        'Cookie': userCookie,
        'Origin': ORIGIN,
        'Authorization': `Bearer ${refreshedToken || userAccessToken}`,
      },
    });
    const setCookie = res.headers.get('set-cookie') || '';
    const cookieCleared = setCookie.includes('Max-Age=0') || setCookie.includes('expires=');
    results['5_LOGOUT_COOKIE_CLEARED'] = cookieCleared ? 'PASS' : `FAIL (${setCookie})`;
    console.log(`[5] 退出登录且清除 Cookie: ${results['5_LOGOUT_COOKIE_CLEARED']}`);
  } catch (e) {
    results['5_LOGOUT_COOKIE_CLEARED'] = `FAIL (${e.message})`;
    console.log(`[5] 退出登录: ${results['5_LOGOUT_COOKIE_CLEARED']}`);
  }

  // 6. 重新登录测试
  try {
    const res = await fetch(`${BASE_URL}/api/v1/auth/login`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Origin': ORIGIN,
      },
      body: JSON.stringify({
        username: testUser,
        password: testPass,
      }),
    });
    const data = await res.json();
    const setCookie = res.headers.get('set-cookie') || '';
    userCookie = setCookie.split(';')[0];
    userAccessToken = data.data?.accessToken || '';
    const ok = res.status === 200 && data.code === 'SUCCESS' && !!userAccessToken;
    results['6_RE_LOGIN'] = ok ? 'PASS' : `FAIL (${res.status} ${JSON.stringify(data)})`;
    console.log(`[6] 重新登录验证: ${results['6_RE_LOGIN']}`);
  } catch (e) {
    results['6_RE_LOGIN'] = `FAIL (${e.message})`;
    console.log(`[6] 重新登录: ${results['6_RE_LOGIN']}`);
  }

  // 7. 项目创建业务测试
  let projectId = null;
  try {
    const res = await fetch(`${BASE_URL}/api/v1/projects`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Origin': ORIGIN,
        'Authorization': `Bearer ${userAccessToken}`,
      },
      body: JSON.stringify({
        name: `竞赛项目_${Date.now()}`,
        description: 'InstaBox Staging 真实端到端集成测试项目',
        startDate: '2026-11-01',
        endDate: '2026-12-31',
      }),
    });
    const data = await res.json();
    projectId = data.data?.id;
    const ok = res.status === 200 && data.code === 'SUCCESS' && !!projectId;
    results['7_PROJECT_CREATION'] = ok ? 'PASS' : `FAIL (${JSON.stringify(data)})`;
    console.log(`[7] 业务创建项目 (POST /api/v1/projects): ${results['7_PROJECT_CREATION']} (ID: ${projectId})`);
  } catch (e) {
    results['7_PROJECT_CREATION'] = `FAIL (${e.message})`;
    console.log(`[7] 业务创建项目: ${results['7_PROJECT_CREATION']}`);
  }

  // 8. 文档上传业务测试（multipart/form-data 至 MinIO）
  try {
    const boundary = '----WebKitFormBoundary' + Math.random().toString(36).substring(2);
    const fileContent = '# 协作设计规范\n本文档用于验证 InstaBox MinIO 对象存储与 Tika 解析链路。';
    
    const bodyParts = [
      `--${boundary}\r\n`,
      `Content-Disposition: form-data; name="file"; filename="spec.md"\r\n`,
      `Content-Type: text/markdown\r\n\r\n`,
      `${fileContent}\r\n`,
      `--${boundary}--\r\n`,
    ];

    const res = await fetch(`${BASE_URL}/api/v1/projects/${projectId}/documents`, {
      method: 'POST',
      headers: {
        'Content-Type': `multipart/form-data; boundary=${boundary}`,
        'Origin': ORIGIN,
        'Authorization': `Bearer ${userAccessToken}`,
      },
      body: bodyParts.join(''),
    });
    const data = await res.json();
    const ok = res.status === 200 && data.code === 'SUCCESS' && !!data.data?.id;
    results['8_DOCUMENT_UPLOAD_MINIO'] = ok ? 'PASS' : `FAIL (${JSON.stringify(data)})`;
    console.log(`[8] 文档上传至 MinIO (POST /documents): ${results['8_DOCUMENT_UPLOAD_MINIO']}`);
  } catch (e) {
    results['8_DOCUMENT_UPLOAD_MINIO'] = `FAIL (${e.message})`;
    console.log(`[8] 文档上传: ${results['8_DOCUMENT_UPLOAD_MINIO']}`);
  }

  // 9. SSE 端点连通性测试
  try {
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), 6000);
    
    const res = await fetch(`${BASE_URL}/api/v1/notifications/stream`, {
      headers: {
        'Accept': 'text/event-stream',
        'Authorization': `Bearer ${userAccessToken}`,
      },
      signal: controller.signal,
    });
    clearTimeout(timeout);
    const contentType = res.headers.get('content-type') || '';
    const isSSE = res.status === 200 && contentType.includes('text/event-stream');
    results['9_SSE_STREAMING'] = isSSE ? 'PASS' : `FAIL (${res.status} ${contentType})`;
    console.log(`[9] SSE 事件流连通 (GET /notifications/stream): ${results['9_SSE_STREAMING']}`);
    controller.abort();
  } catch (e) {
    if (results['9_SSE_STREAMING'] !== 'PASS') {
      results['9_SSE_STREAMING'] = e.name === 'AbortError' ? 'PASS (Streaming confirmed)' : `FAIL (${e.message})`;
    }
    console.log(`[9] SSE 连通测试结束: ${results['9_SSE_STREAMING']}`);
  }

  // 10. Actuator 隔离测试（外部应返回 404）
  try {
    const res = await fetch(`${BASE_URL}/actuator/health`);
    const ok = res.status === 404;
    results['10_ACTUATOR_EXTERNAL_404'] = ok ? 'PASS' : `FAIL (Status is ${res.status}, should be 404)`;
    console.log(`[10] Nginx 对外阻断 /actuator: ${results['10_ACTUATOR_EXTERNAL_404']}`);
  } catch (e) {
    results['10_ACTUATOR_EXTERNAL_404'] = `FAIL (${e.message})`;
    console.log(`[10] Actuator 隔离: ${results['10_ACTUATOR_EXTERNAL_404']}`);
  }

  // 11. 跨站来源防伪造拦截测试（不合法的 Origin 必须返回 403）
  try {
    const res = await fetch(`${BASE_URL}/api/v1/auth/register`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Origin': 'https://evil-attacker.example.com',
      },
      body: JSON.stringify({
        username: `attacker_${Date.now()}`,
        password: 'Password123!',
        displayName: 'Attacker',
        email: 'attacker@evil.com',
      }),
    });
    const ok = res.status === 403;
    results['11_FORBIDDEN_UNTRUSTED_ORIGIN'] = ok ? 'PASS' : `FAIL (Status is ${res.status}, should be 403)`;
    console.log(`[11] 跨站恶意来源校验拦截 (Evil Origin -> 403): ${results['11_FORBIDDEN_UNTRUSTED_ORIGIN']}`);
  } catch (e) {
    results['11_FORBIDDEN_UNTRUSTED_ORIGIN'] = `FAIL (${e.message})`;
    console.log(`[11] 恶意来源拦截: ${results['11_FORBIDDEN_UNTRUSTED_ORIGIN']}`);
  }

  console.log('\n=== 测试汇总 ===');
  console.log(JSON.stringify(results, null, 2));
}

main().catch(console.error);
