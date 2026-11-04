import { execFileSync } from 'child_process';
import { setTimeout } from 'timers/promises';

const sessionId = process.argv[2] || 'lryw';

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
  try {
    const parsed = JSON.parse(out);
    return {
      context_id: parsed.context_id,
      observation_id: parsed.observation_id
    };
  } catch (e) {
    const ctxMatch = out.match(/"context_id":\s*"([^"]+)"/);
    const obsMatch = out.match(/"observation_id":\s*"([^"]+)"/);
    return {
      context_id: ctxMatch ? ctxMatch[1] : null,
      observation_id: obsMatch ? obsMatch[1] : null
    };
  }
}

async function captureOne(url, outPath, width, height) {
  console.log(`\n--- Capturing ${url} (${width}x${height}) -> ${outPath} ---`);
  let state = sync();

  if (url) {
    try {
      const navArgs = ['navigate', url, '--session', sessionId];
      if (state.context_id) navArgs.push('--context', state.context_id);
      if (state.observation_id) navArgs.push('--observation', state.observation_id);
      runBsk(navArgs);
    } catch (e) {
      console.warn('Nav warning:', e.message);
    }
    await setTimeout(600);
    state = sync();
  }

  try {
    const resizeArgs = ['window', 'resize', '--session', sessionId, '--width', String(width), '--height', String(height)];
    if (state.context_id) resizeArgs.push('--context', state.context_id);
    if (state.observation_id) resizeArgs.push('--observation', state.observation_id);
    runBsk(resizeArgs);
  } catch (e) {
    console.warn('Resize warning:', e.message);
  }

  await setTimeout(400);

  runBsk(['screenshot', '--session', sessionId, '--out', outPath]);
  console.log(`Saved: ${outPath}`);
}

async function run() {
  const matrix = [
    // 登录
    { url: 'http://localhost:5173/login', out: 'docs/acceptance-evidence/2026-11-03/auth-visual-upgrade/final/login-1440.png', w: 1440, h: 900 },
    { url: 'http://localhost:5173/login', out: 'docs/acceptance-evidence/2026-11-03/auth-visual-upgrade/final/login-1024.png', w: 1024, h: 768 },
    { url: 'http://localhost:5173/login', out: 'docs/acceptance-evidence/2026-11-03/auth-visual-upgrade/final/login-768.png', w: 768, h: 1024 },
    { url: 'http://localhost:5173/login', out: 'docs/acceptance-evidence/2026-11-03/auth-visual-upgrade/final/login-390.png', w: 390, h: 844 },

    // 注册
    { url: 'http://localhost:5173/register', out: 'docs/acceptance-evidence/2026-11-03/auth-visual-upgrade/final/register-1440.png', w: 1440, h: 900 },
    { url: 'http://localhost:5173/register', out: 'docs/acceptance-evidence/2026-11-03/auth-visual-upgrade/final/register-1024.png', w: 1024, h: 768 },
    { url: 'http://localhost:5173/register', out: 'docs/acceptance-evidence/2026-11-03/auth-visual-upgrade/final/register-768.png', w: 768, h: 1024 },
    { url: 'http://localhost:5173/register', out: 'docs/acceptance-evidence/2026-11-03/auth-visual-upgrade/final/register-390.png', w: 390, h: 844 },

    // 忘记密码
    { url: 'http://localhost:5173/forgot-password', out: 'docs/acceptance-evidence/2026-11-03/auth-visual-upgrade/final/forgot-password-1440.png', w: 1440, h: 900 },
    { url: 'http://localhost:5173/forgot-password', out: 'docs/acceptance-evidence/2026-11-03/auth-visual-upgrade/final/forgot-password-1024.png', w: 1024, h: 768 },
    { url: 'http://localhost:5173/forgot-password', out: 'docs/acceptance-evidence/2026-11-03/auth-visual-upgrade/final/forgot-password-390.png', w: 390, h: 844 },

    // 重置密码（正常表单与无效 Token）
    { url: 'http://localhost:5173/reset-password?token=mock-valid-token-example', out: 'docs/acceptance-evidence/2026-11-03/auth-visual-upgrade/final/reset-password-form-1440.png', w: 1440, h: 900 },
    { url: 'http://localhost:5173/reset-password', out: 'docs/acceptance-evidence/2026-11-03/auth-visual-upgrade/final/reset-password-invalid-1440.png', w: 1440, h: 900 },
    { url: 'http://localhost:5173/reset-password?token=mock-valid-token-example', out: 'docs/acceptance-evidence/2026-11-03/auth-visual-upgrade/final/reset-password-1024.png', w: 1024, h: 768 },
    { url: 'http://localhost:5173/reset-password?token=mock-valid-token-example', out: 'docs/acceptance-evidence/2026-11-03/auth-visual-upgrade/final/reset-password-390.png', w: 390, h: 844 },
  ];

  for (const item of matrix) {
    await captureOne(item.url, item.out, item.w, item.h);
  }
  console.log('\nAll matrix screenshots captured successfully!');
}

run().catch(err => {
  console.error('Run matrix failed:', err);
  process.exit(1);
});
