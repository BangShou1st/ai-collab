/**
 * 验证注册接口 IP 限流: 每小时最多 10 次
 */
const BASE_URL = 'https://8443-agqyc3kteqehqgazwefpmhwulu.instabox.tech';
const ORIGIN = BASE_URL;

async function testRateLimit() {
  console.log('=== 开始注册接口 IP 限流压力验证 ===\n');
  let hitRateLimit = false;
  let rejectedAt = -1;

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

    const data = await res.json().catch(() => ({}));
    console.log(`[请求 #${i}] HTTP ${res.status} Code: ${data.code || 'N/A'}`);

    if (res.status === 429 || data.code === 'RATE_LIMIT_EXCEEDED' || data.code === 'TOO_MANY_REQUESTS') {
      hitRateLimit = true;
      rejectedAt = i;
      console.log(`\n✅ 成功触发限流！在第 ${i} 次请求被拒绝拦截: HTTP ${res.status}`);
      break;
    }
  }

  if (!hitRateLimit) {
    console.error('\n❌ 限流未生效！15 次请求均未被拒绝。');
    process.exit(1);
  }
}

testRateLimit().catch((err) => {
  console.error('\n❌ 异常:', err);
  process.exit(1);
});
