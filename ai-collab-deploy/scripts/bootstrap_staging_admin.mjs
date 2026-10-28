/**
 * 受控的 Staging 首管理员初始化脚本
 * 1. 通过常规公开注册接口创建 staging_admin 账号
 * 2. 验证新注册用户绝非管理员 (systemAdmin === false)
 * 3. 通过本地直接执行受控 SQL 赋予系统管理员权限 (system_admin = true)
 * 4. 重新认证登录并请求 /api/v1/auth/me，断言已成功获得系统管理员权限
 */
import { execSync } from 'child_process';
import crypto from 'crypto';

const BASE_URL = 'https://8443-agqyc3kteqehqgazwefpmhwulu.instabox.tech';
const ORIGIN = BASE_URL;

async function bootstrap() {
  console.log('=== 开始 Staging 首管理员受控初始化 ===\n');

  // 1. 检查数据库中是否已有系统管理员
  const existingCountStr = execSync(
    "docker exec ai-collab-staging-postgres-1 psql -U ai_collab_staging -d ai_collab_staging -t -A -c 'SELECT count(*) FROM app_user WHERE system_admin = true;'"
  ).toString().trim();
  
  const existingCount = parseInt(existingCountStr, 10);
  console.log(`[1] 当前库中系统管理员数量: ${existingCount}`);

  if (existingCount > 0) {
    console.log('库中已存在系统管理员，为确保幂等与安全，不再重复初始化。\n');
    return;
  }

  // 2. 生成安全随机凭据（用户名仅字母、数字与下划线）
  const adminUsername = 'staging_admin';
  const adminPassword = 'Admin_' + crypto.randomBytes(12).toString('hex') + '!';
  
  console.log('[2] 通过公开注册接口创建账号:', adminUsername);
  const regRes = await fetch(`${BASE_URL}/api/v1/auth/register`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'Origin': ORIGIN,
    },
    body: JSON.stringify({
      username: adminUsername,
      password: adminPassword,
      displayName: 'Staging System Administrator',
      email: 'admin@ai-collab.internal',
    }),
  });

  const regData = await regRes.json();
  if (regData.code !== 'SUCCESS') {
    throw new Error(`注册接口调用失败: ${JSON.stringify(regData)}`);
  }

  const initialIsAdmin = regData.data?.user?.systemAdmin === true;
  console.log(`[3] 验证公开注册初始权限: systemAdmin = ${initialIsAdmin} (必须为 false)`);
  if (initialIsAdmin) {
    throw new Error('严重安全违规: 公开注册用户被自动赋予了管理员角色！');
  }

  // 3. 通过受控本地 SQL 将首个管理员提权
  console.log('[4] 执行受控 SQL 提权: UPDATE app_user SET system_admin = true WHERE username = staging_admin');
  execSync(
    `docker exec ai-collab-staging-postgres-1 psql -U ai_collab_staging -d ai_collab_staging -c "UPDATE app_user SET system_admin = true WHERE username = '${adminUsername}';"`
  );

  // 4. 重新登录并调用 /api/v1/auth/me 验证
  console.log('[5] 重新登录验证管理员权限...');
  const loginRes = await fetch(`${BASE_URL}/api/v1/auth/login`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'Origin': ORIGIN,
    },
    body: JSON.stringify({
      username: adminUsername,
      password: adminPassword,
    }),
  });

  const loginData = await loginRes.json();
  const accessToken = loginData.data?.accessToken;
  if (!accessToken) {
    throw new Error(`管理员重新登录失败: ${JSON.stringify(loginData)}`);
  }

  const meRes = await fetch(`${BASE_URL}/api/v1/auth/me`, {
    headers: {
      'Authorization': `Bearer ${accessToken}`,
      'Origin': ORIGIN,
    },
  });

  const meData = await meRes.json();
  const elevatedAdmin = meData.data?.systemAdmin === true;
  console.log(`[6] /api/v1/auth/me 最终校验: systemAdmin = ${elevatedAdmin}`);

  if (!elevatedAdmin) {
    throw new Error('首管理员提权后 /me 校验未通过！');
  }

  console.log('\n✅ Staging 首管理员受控初始化成功完成！');
}

bootstrap().catch((err) => {
  console.error('\n❌ 初始化失败:', err.message);
  process.exit(1);
});
