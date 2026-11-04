import { execFileSync } from 'child_process';
import { setTimeout } from 'timers/promises';

const sessionId = process.argv[2] || 'nisx';
const evidenceDir = 'docs/acceptance-evidence/2026-11-03/workbench-fix';

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

async function captureScreenshot(outPath, width = 1440, height = 900) {
  console.log(`Resizing to ${width}x${height} and capturing -> ${outPath}`);
  let s = sync();
  try {
    runBsk(['window', 'resize', '--session', sessionId, '--context', s.context_id, '--observation', s.observation_id, '--width', String(width), '--height', String(height)]);
  } catch (e) {
    console.warn('Resize note:', e.message);
  }
  await setTimeout(700);

  s = sync();
  try {
    runBsk(['screenshot', '--session', sessionId, '--out', outPath]);
  } catch (e) {
    console.warn('Screenshot retry...');
    await setTimeout(500);
    runBsk(['screenshot', '--session', sessionId, '--out', outPath]);
  }
  await setTimeout(400);
}

async function navigatePage(url) {
  console.log(`Navigating to ${url}...`);
  try {
    runBsk(['navigate', url, '--session', sessionId]);
  } catch (e) {
    console.warn('Navigate note:', e.message);
  }
  await setTimeout(1400);
}

async function evalCode(code) {
  let s = sync();
  runBsk(['evaluate', code, '--session', sessionId, '--context', s.context_id, '--observation', s.observation_id]);
  await setTimeout(700);
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
  console.log(`=== Starting Targeted Verification on session: ${sessionId} ===\n`);

  // 0. 登录页确认（无竞赛版标签）
  await navigatePage('http://localhost:5173/login');
  await captureScreenshot(`${evidenceDir}/01-login-clean-no-tag.png`, 1440, 900);

  // 1. 纯新用户场景（newuser_alpha，零项目零任务）
  console.log('\n--- Scenario 1: Brand New User (0 projects, 0 tasks) ---');
  await loginUser('newuser_alpha', 'TestPass123!');
  await evalCode(`(() => {
    localStorage.clear();
    window.location.reload();
  })()`);
  await setTimeout(2000);

  // 截图新用户工作台：展示 0/2 核心就绪，第 4 指标卡为“最近项目（最多展示 5 项）”
  await captureScreenshot(`${evidenceDir}/02-brand-new-user-workbench-expanded.png`, 1440, 900);

  // 2. 老用户零待办场景（veteran_zero_todo，有成熟项目但个人待办为 0）
  console.log('\n--- Scenario 2: Veteran User (Has project, but 0 pending tasks) ---');
  await loginUser('veteran_zero_todo', 'TestPass123!');
  await evalCode(`(() => {
    localStorage.clear();
    window.location.reload();
  })()`);
  await setTimeout(2000);

  // 2.1 验证老用户默认免打扰：引导卡片默认收起！
  await captureScreenshot(`${evidenceDir}/03-veteran-zero-todo-default-hidden.png`, 1440, 900);

  // 2.2 用户主动点击顶部「新手引导」按钮展开
  console.log('Expanding guide card for veteran user...');
  await evalCode(`(() => {
    const buttons = Array.from(document.querySelectorAll('button'));
    const btn = buttons.find(b => b.textContent.includes('新手引导'));
    if (btn) btn.click();
  })()`);
  await setTimeout(1000);

  // 截图老用户展开后的引导：
  // - 步骤 2 链接包含 /members
  // - 步骤 3 显示为“看板协同 / 体验任务看板”（非未完成）
  // - 指标栏精确标明“最近项目（最多展示 5 项）”
  await captureScreenshot(`${evidenceDir}/04-veteran-zero-todo-guide-expanded.png`, 1440, 900);

  // 2.3 验证点击步骤 2「管理成员」能够跳转到 /projects/:id/members
  console.log('Testing member management link navigation...');
  await evalCode(`(() => {
    const links = Array.from(document.querySelectorAll('a'));
    const memberLink = links.find(a => a.textContent.includes('管理成员'));
    if (memberLink) memberLink.click();
  })()`);
  await setTimeout(2000);

  await captureScreenshot(`${evidenceDir}/05-navigated-to-project-members.png`, 1440, 900);

  console.log('\n=== All Verification Screenshots Successfully Captured! ===');
}

main().catch(err => {
  console.error('Verification suite error:', err);
  process.exit(1);
});
