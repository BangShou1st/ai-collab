import { execFileSync } from 'child_process';
import { setTimeout } from 'timers/promises';

const sessionId = process.argv[2] || 'lryw';
const targetUrl = process.argv[3];
const outPath = process.argv[4];
const width = parseInt(process.argv[5] || '1440', 10);
const height = parseInt(process.argv[6] || '900', 10);

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
  let state = sync();

  if (targetUrl) {
    console.log(`[1] Navigating to ${targetUrl}...`);
    try {
      const navArgs = ['navigate', targetUrl, '--session', sessionId];
      if (state.context_id) navArgs.push('--context', state.context_id);
      if (state.observation_id) navArgs.push('--observation', state.observation_id);
      runBsk(navArgs);
    } catch (e) {
      console.warn('Navigate warning:', e.message);
    }
    await setTimeout(600);
    state = sync();
  }

  console.log(`[2] Resizing window to ${width}x${height}...`);
  try {
    const resizeArgs = ['window', 'resize', '--session', sessionId, '--width', String(width), '--height', String(height)];
    if (state.context_id) resizeArgs.push('--context', state.context_id);
    if (state.observation_id) resizeArgs.push('--observation', state.observation_id);
    runBsk(resizeArgs);
  } catch (e) {
    console.warn('Resize warning:', e.message);
  }

  await setTimeout(400);

  console.log(`[3] Taking screenshot to ${outPath}...`);
  runBsk(['screenshot', '--session', sessionId, '--out', outPath]);
  console.log(`Screenshot saved to ${outPath}`);
}

main().catch(err => {
  console.error('Capture error:', err);
  process.exit(1);
});
