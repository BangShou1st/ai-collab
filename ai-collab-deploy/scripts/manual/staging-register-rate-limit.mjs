/**
 * 验证注册接口 IP 限流: 每小时最多 10 次（手动执行，不属于任何自动门禁）。
 *
 * 会对目标环境发起最多 15 次真实注册请求。
 *
 * 用法:
 *   STAGING_BASE_URL=https://<staging-host> ALLOW_REMOTE_WRITE=1 node ai-collab-deploy/scripts/manual/staging-register-rate-limit.mjs
 *
 * 退出码: 0 触发限流；1 限流未生效或出现非预期状态码（立即中止）；2 参数/目标地址校验未通过。
 */
import { resolveTarget } from './remote-target.mjs';

const BASE_URL = resolveTarget('staging-register-rate-limit.mjs');
const ORIGIN = BASE_URL;

async function testRateLimit() {
  console.log(`=== 开始注册接口 IP 限流压力验证: ${BASE_URL} ===\n`);
  let hitRateLimit = false;

  for (let i = 1; i <= 15; i++) {
    const res = await fetch(`${BASE_URL}/api/v1/auth/register`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Origin': ORIGIN,
      },
      body: JSON.stringify({
        username: `ratelimit_user_${Date.now()}_${i}`,
        password: 'Password123!',
        displayName: `Rate Limit Test ${i}`,
        email: `ratelimit_${Date.now()}_${i}@example.org`,
      }),
    });

    const text = await res.text();
    let data = {};
    try {
      data = JSON.parse(text);
    } catch {
      data = { raw: text.slice(0, 200) };
    }
    console.log(`[请求 #${i}] HTTP ${res.status} Code: ${data.code || 'N/A'}`);

    if (res.status === 429) {
      hitRateLimit = true;
      console.log(`\nPASS: 在第 ${i} 次请求触发限流: HTTP ${res.status} Code: ${data.code || 'N/A'}`);
      break;
    }
    // 未触发限流前，注册只允许成功（200）；其他任何状态码都说明环境或请求异常，立即中止。
    if (res.status !== 200 || data.code !== 'SUCCESS') {
      throw new Error(`第 ${i} 次注册返回非预期结果 HTTP ${res.status}（期望 200 SUCCESS 或 429）: ${JSON.stringify(data)}`);
    }
  }

  if (!hitRateLimit) {
    throw new Error('限流未生效: 15 次注册请求均未被拒绝');
  }
}

try {
  await testRateLimit();
} catch (err) {
  console.error(`\nFAIL: ${err?.message || err}`);
  process.exitCode = 1;
}
