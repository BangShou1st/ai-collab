import { execFileSync } from 'child_process';
import { setTimeout } from 'timers/promises';

const sessionId = process.argv[2] || 'jbch';
const evidenceDir = 'docs/acceptance-evidence/2026-11-03/onboarding-workbench-upgrade/final';

function runBsk(args) {
  try {
    return execFileSync('bsk', args, { encoding: 'utf-8', stdio: ['pipe', 'pipe', 'pipe'] });
  } catch (err) {
    const combined = (err.stdout || '') + '\n' + (err.stderr || '');
    const e = new Error(combined.trim() || err.message);
    e.stdout = err.stdout;
    e.stderr = err.stderr;
    throw e;
  }
}

function sync() {
  const out = runBsk(['cooperate', 'sync', '--session', sessionId, '--json']);
  return JSON.parse(out);
}

async function captureScreenshot(outPath, width, height) {
  console.log(`Setting size to ${width}x${height} and capturing -> ${outPath}`);
  let s = sync();
  try {
    runBsk(['window', 'resize', '--session', sessionId, '--context', s.context_id, '--observation', s.observation_id, '--width', String(width), '--height', String(height)]);
  } catch (e) {
    console.warn('Resize warning:', e.message);
  }
  await setTimeout(700);

  s = sync();
  try {
    runBsk(['screenshot', '--session', sessionId, '--out', outPath]);
  } catch (e) {
    console.warn('Screenshot retry...');
    await setTimeout(400);
    runBsk(['screenshot', '--session', sessionId, '--out', outPath]);
  }
  await setTimeout(400);
}

async function navigatePage(url) {
  console.log(`Navigating to ${url}...`);
  try {
    runBsk(['navigate', url, '--session', sessionId]);
  } catch (e) {
    console.warn('Navigate error:', e.message);
  }
  await setTimeout(1400);
}

async function evalCode(code) {
  let s = sync();
  runBsk(['evaluate', code, '--session', sessionId, '--context', s.context_id, '--observation', s.observation_id]);
  await setTimeout(600);
}

async function loginUser(username, password) {
  console.log(`Logging in user: ${username}...`);
  await evalCode(`(() => {
    fetch('/api/v1/auth/login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username: '${username}', password: '${password}' }),
      credentials: 'include'
    }).then(r => r.json()).then(res => {
      window.location.href = '/home';
    });
  })()`);
  await setTimeout(2500);
}

async function main() {
  console.log(`=== Starting Comprehensive Acceptance Suite on session: ${sessionId} ===\n`);

  // 先进入系统
  await navigatePage('http://localhost:5173/login');

  // ==========================================
  // 阶段 1：全新纯净新用户空工作台 (fresh_user_01)
  // ==========================================
  console.log('\n--- Phase 1: Pure New User Empty Workbench ---');
  await loginUser('fresh_user_01', 'Password123!');

  // 确保处于展开状态（清除 localStorage 记录）
  await evalCode(`(() => {
    localStorage.clear();
    window.location.reload();
  })()`);
  await setTimeout(2000);

  // 1.1 截取新用户空工作台全分辨率展开态
  await captureScreenshot(`${evidenceDir}/new-user-workbench-1440-expanded.png`, 1440, 900);
  await captureScreenshot(`${evidenceDir}/new-user-workbench-1024-expanded.png`, 1024, 768);
  await captureScreenshot(`${evidenceDir}/new-user-workbench-768-expanded.png`, 768, 1024);
  await captureScreenshot(`${evidenceDir}/new-user-workbench-390-expanded.png`, 390, 844);

  // 1.2 收起新手引导
  console.log('\n--- Phase 1.2: User Dismisses Guide ---');
  await evalCode(`(() => {
    const btn = document.querySelector('.dismiss-btn');
    if (btn) btn.click();
  })()`);
  await setTimeout(800);
  await captureScreenshot(`${evidenceDir}/new-user-workbench-1440-dismissed.png`, 1440, 900);
  await captureScreenshot(`${evidenceDir}/new-user-workbench-390-dismissed.png`, 390, 844);

  // 1.3 恢复展开新手引导
  console.log('\n--- Phase 1.3: User Restores Guide ---');
  await evalCode(`(() => {
    const buttons = Array.from(document.querySelectorAll('button'));
    const restoreBtn = buttons.find(b => b.textContent.includes('新手引导'));
    if (restoreBtn) restoreBtn.click();
  })()`);
  await setTimeout(800);
  await captureScreenshot(`${evidenceDir}/new-user-workbench-1440-restored.png`, 1440, 900);

  // ==========================================
  // 阶段 2：已有项目与待办任务的活跃工作台 (test_new_001)
  // ==========================================
  console.log('\n--- Phase 2: Active User with Projects & Tasks ---');
  await loginUser('test_new_001', 'Password123!');
  await setTimeout(2000);

  // 截取活跃工作台多分辨率
  await captureScreenshot(`${evidenceDir}/active-workbench-1440.png`, 1440, 900);
  await captureScreenshot(`${evidenceDir}/active-workbench-1024.png`, 1024, 768);
  await captureScreenshot(`${evidenceDir}/active-workbench-768.png`, 768, 1024);
  await captureScreenshot(`${evidenceDir}/active-workbench-390.png`, 390, 844);

  // ==========================================
  // 阶段 3：登录页（已删除「竞赛版」Tag）
  // ==========================================
  console.log('\n--- Phase 3: Login Page (Tag Removed) ---');
  await evalCode(`(() => {
    fetch('/api/v1/auth/logout', { method: 'POST', credentials: 'include' }).then(() => {
      window.location.href = '/login';
    });
  })()`);
  await setTimeout(2000);

  await captureScreenshot(`${evidenceDir}/login-1440-tag-removed.png`, 1440, 900);
  await captureScreenshot(`${evidenceDir}/login-1024-tag-removed.png`, 1024, 768);
  await captureScreenshot(`${evidenceDir}/login-768-tag-removed.png`, 768, 1024);
  await captureScreenshot(`${evidenceDir}/login-390-tag-removed.png`, 390, 844);

  console.log('\n=== All Acceptance Matrix Evidence Successfully Captured! ===');
}

main().catch(err => {
  console.error('Suite error:', err);
  process.exit(1);
});
