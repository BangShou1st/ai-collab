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

async function main() {
  // 1. 注册表单校验提示截屏 (1440x900)
  console.log('Testing register form validation feedback...');
  let state = sync();
  runBsk(['window', 'resize', '--session', sessionId, '--context', state.context_id, '--observation', state.observation_id, '--width', '1440', '--height', '900']);
  await setTimeout(400);

  state = sync();
  runBsk(['navigate', 'http://localhost:5173/register', '--session', sessionId, '--context', state.context_id, '--observation', state.observation_id]);
  await setTimeout(800);

  // 用 evaluate 在表单里填入两次不一致密码
  state = sync();
  runBsk(['evaluate', `(() => {
    const inputs = document.querySelectorAll('.el-input__inner');
    if (inputs.length >= 5) {
      inputs[0].value = 'test_user_001';
      inputs[0].dispatchEvent(new Event('input', { bubbles: true }));
      inputs[1].value = '测试成员';
      inputs[1].dispatchEvent(new Event('input', { bubbles: true }));
      inputs[3].value = 'Password123';
      inputs[3].dispatchEvent(new Event('input', { bubbles: true }));
      inputs[4].value = 'MismatchPassword456';
      inputs[4].dispatchEvent(new Event('input', { bubbles: true }));
    }
  })()`, '--session', sessionId, '--context', state.context_id, '--observation', state.observation_id]);
  await setTimeout(600);

  runBsk(['screenshot', '--session', sessionId, '--out', 'docs/acceptance-evidence/2026-11-03/auth-visual-upgrade/final/register-validation-feedback.png']);
  console.log('Saved register-validation-feedback.png');

  // 2. 登录失败反馈截屏 (1440x900)
  console.log('Testing login error feedback...');
  state = sync();
  runBsk(['navigate', 'http://localhost:5173/login', '--session', sessionId, '--context', state.context_id, '--observation', state.observation_id]);
  await setTimeout(800);

  state = sync();
  runBsk(['evaluate', `(() => {
    const inputs = document.querySelectorAll('.el-input__inner');
    if (inputs.length >= 2) {
      inputs[0].value = 'nonexistent_user';
      inputs[0].dispatchEvent(new Event('input', { bubbles: true }));
      inputs[1].value = 'wrong_password_999';
      inputs[1].dispatchEvent(new Event('input', { bubbles: true }));
    }
    const btn = document.querySelector('.submit-button');
    if (btn) btn.click();
  })()`, '--session', sessionId, '--context', state.context_id, '--observation', state.observation_id]);
  await setTimeout(1200);

  runBsk(['screenshot', '--session', sessionId, '--out', 'docs/acceptance-evidence/2026-11-03/auth-visual-upgrade/final/login-error-feedback.png']);
  console.log('Saved login-error-feedback.png');
}

main().catch(err => {
  console.error('Interaction test failed:', err);
  process.exit(1);
});
