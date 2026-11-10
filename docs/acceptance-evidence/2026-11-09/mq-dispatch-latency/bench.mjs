#!/usr/bin/env node
// bench.mjs —— ai-collab MQ 派发延迟压测受控负载脚本（Node 22+，零第三方依赖）。
// 用法示例：
//   node bench.mjs setup   --env .env --name "BENCH-MQ" --out data/bench-env.json
//   node bench.mjs docs    --env .env --project <uuid> --count 60 --concurrency 5 --label mq --out data/docs-mq.csv --timeout 120
//   node bench.mjs runs    --env .env --project <uuid> --count 20 --label mq --out data/runs-mq.csv
// 输出均为 CSV，写在使用方给定的 --out 路径（约定放在 target/bench/data/ 下）。
// 时钟口径：全部时间戳取本进程 performance.now()/Date.now()（宿主机时钟，与后端同机）。

import fs from 'node:fs';
import path from 'node:path';
import cp from 'node:child_process';

function parseArgs(argv) {
  const args = { _: null };
  for (let i = 2; i < argv.length; i++) {
    const a = argv[i];
    if (a.startsWith('--')) {
      const key = a.slice(2);
      const next = argv[i + 1];
      if (next === undefined || next.startsWith('--')) args[key] = true;
      else { args[key] = next; i++; }
    }
  }
  return args;
}

function readEnv(file) {
  const out = {};
  for (const line of fs.readFileSync(file, 'utf8').split(/\r?\n/)) {
    const m = line.match(/^\s*([A-Z][A-Z0-9_]*)\s*=(.*)$/);
    if (m) out[m[1]] = m[2].trim();
  }
  return out;
}

const args = parseArgs(process.argv);
const BASES = (args.bases || args.base || 'http://localhost:8080').split(',').map(s => s.trim()).filter(Boolean);
let baseIdx = 0;
const BASE = () => BASES[(baseIdx++) % BASES.length];
const ENV = readEnv(args.env || '.env');

function die(msg) { console.error('FATAL: ' + msg); process.exit(1); }

async function api(pathname, { method = 'GET', token, json, form, origin = true } = {}) {
  const headers = {};
  if (origin) headers['Origin'] = 'http://localhost:5173';
  if (token) headers['Authorization'] = 'Bearer ' + token;
  let body;
  if (json !== undefined) { headers['Content-Type'] = 'application/json'; body = JSON.stringify(json); }
  else if (form) body = form;
  const started = performance.now();
  let res;
  try {
    res = await fetch(BASE() + pathname, { method, headers, body });
  } catch (e) {
    return { status: 0, payload: null, ms: performance.now() - started, doneMs: Date.now(), donePerf: started, netError: String(e?.cause?.code || e?.message || e) };
  }
  const done = performance.now();
  const text = await res.text();
  let payload;
  try { payload = JSON.parse(text); } catch { payload = null; }
  return { status: res.status, payload, ms: done - started, doneMs: Date.now(), donePerf: done };
}

async function login() {
  const r = await api('/api/v1/auth/login', {
    method: 'POST',
    json: { username: ENV.DEMO_OWNER_USERNAME, password: ENV.DEMO_OWNER_PASSWORD },
  });
  if (r.payload?.code !== 'SUCCESS' || !r.payload?.data?.accessToken) {
    die('login failed: ' + JSON.stringify(r.payload)?.slice(0, 300));
  }
  return r.payload.data.accessToken;
}

function ensureDir(p) {
  fs.mkdirSync(path.dirname(p), { recursive: true });
}

function makeDocContent(i, label) {
  // 约 2KB 纯文本：Tika 解析 + 1-2 个 chunk + 一次 embedding 调用，尽量压缩
  // documentTaskExecutor（2-4 线程）的单文档占用时长，使派发触发时延不被
  // 处理线程池排队完全淹没（突发组的排队效应单独呈现，见报告方法学）。
  const para = `BENCH ${label} 文档 ${i}：本地压测语料，用于派发延迟测量。`;
  const body = new Array(18).fill(para).join('\n');
  return body + `\nseq=${i}\n`;
}

// ---- setup：创建专用项目，输出 {projectId} ----
async function setup() {
  const token = await login();
  const name = args.name || 'BENCH';
  const r = await api('/api/v1/projects', {
    method: 'POST', token,
    json: { name: `${name}-${Date.now()}`, description: 'bench: mq dispatch latency evidence', type: 'SOFTWARE_TRAINING' },
  });
  if (r.payload?.code !== 'SUCCESS') die('create project failed: ' + JSON.stringify(r.payload)?.slice(0, 300));
  const projectId = r.payload.data.id;
  ensureDir(args.out || 'data/bench-env.json');
  fs.writeFileSync(args.out || 'data/bench-env.json', JSON.stringify({ projectId, createdAt: new Date().toISOString() }, null, 2));
  console.log('projectId=' + projectId);
}

// ---- docs：上传 N 份文档 + 同步轮询状态，输出每份 claim 时延 ----
// 时延口径（全部客户端时钟）：
//   t0 = upload 响应到达时刻；t1 = list 接口首次观测到 status != UPLOADED（即 claim 完成，通常为 PARSING）；
//   latencyMs = t1 - t0。轮询间隔 60ms，粒度误差 ≤ 60ms + 单次请求耗时，P99 结果以该粒度解读。
async function docs() {
  const token = await login();
  const projectId = args.project; if (!projectId) die('--project required');
  const count = Number(args.count || 50);
  const conc = Number(args.concurrency || 5);
  const label = args.label || 'run';
  const out = args.out || `data/docs-${label}.csv`;
  const timeoutMs = Number(args.timeout || 120) * 1000;
  ensureDir(out);

  const firstSeen = new Map(); // docId -> { ts, status }
  const uploadRows = [];       // { seq, docId, t0, createdAt }
  let stop = false;

  const poller = (async () => {
    while (!stop) {
      const r = await api(`/api/v1/projects/${projectId}/documents?page=0&size=200`, { token }).catch(() => null);
      const list = r?.payload?.data?.content || r?.payload?.data || [];
      const now = Date.now();
      for (const d of list) {
        if (firstSeen.has(d.id)) continue;
        if (d.status && d.status !== 'UPLOADED') firstSeen.set(d.id, { ts: now, status: d.status });
      }
      await new Promise(s => setTimeout(s, Number(args.interval || 60)));
    }
  })();

  const t0 = Date.now();
  let seq = 0;
  async function worker() {
    while (true) {
      const my = seq++;
      if (my >= count) return;
      if (args['pace-ms']) await new Promise(s => setTimeout(s, Number(args['pace-ms']) * (my / Math.max(1, conc))));
      const blob = new Blob([makeDocContent(my, label)], { type: 'text/plain' });
      const form = new FormData();
      form.append('file', blob, `bench-${label}-${String(my).padStart(3, '0')}.txt`);
      form.append('displayName', `bench-${label}-${String(my).padStart(3, '0')}`);
      const r = await api(`/api/v1/projects/${projectId}/documents`, { method: 'POST', token, form });
      // 故障注入：完成 kill-after 份成功上传后立即 kill 后端进程，之后的上传标记 KILLED 中止
      if (r.payload?.code === 'SUCCESS' && args['kill-after'] && uploadRows.length >= Number(args['kill-after']) && args['kill-pid']) {
        try { cp.execSync(`taskkill /F /PID ${args['kill-pid']}`, { stdio: 'ignore' }); console.error(`KILLED backend pid=${args['kill-pid']} after ${uploadRows.length} uploads at ${Date.now()}`); } catch (e) { console.error('taskkill failed: ' + e.message); }
        delete args['kill-after'];
      }
      if (r.status !== 202 && r.payload?.code !== 'SUCCESS') {
        console.error(`upload ${my} failed: HTTP ${r.status} ${JSON.stringify(r.payload)?.slice(0, 200)}`);
        uploadRows.push({ seq: my, docId: '', t0: r.doneMs, createdAt: '', error: r.status });
        continue;
      }
      const d = r.payload.data;
      uploadRows.push({ seq: my, docId: d.id, t0: r.doneMs, createdAt: d.createdAt ?? '', error: '' });
    }
  }
  await Promise.all(Array.from({ length: conc }, worker));
  const uploadedAt = Date.now();

  // 等待全部出现首次非 UPLOADED 状态
  const deadline = t0 + timeoutMs;
  while (Date.now() < deadline) {
    if (uploadRows.every(r => !r.docId || firstSeen.has(r.docId))) break;
    await new Promise(s => setTimeout(s, 100));
  }
  stop = true;
  await poller.catch(() => {});

  const lines = ['seq,docId,uploadDoneMs,respCreatedAt,firstSeenMs,firstSeenStatus,latencyMs,concurrency,label'];
  for (const r of uploadRows.sort((a, b) => a.seq - b.seq)) {
    const f = r.docId ? firstSeen.get(r.docId) : null;
    const lat = f ? f.ts - r.t0 : '';
    lines.push([r.seq, r.docId, r.t0, r.createdAt, f?.ts ?? '', f?.status ?? '', lat, conc, label].join(','));
  }
  fs.writeFileSync(out, lines.join('\n') + '\n');
  const ok = uploadRows.filter(r => r.docId);
  const lat = ok.map(r => firstSeen.get(r.docId)).filter(Boolean).map(f => f).length;
  console.log(`uploaded=${ok.length}/${count} observedClaims=${firstSeen.size} elapsedMs=${Date.now() - t0} out=${out}`);
  if (lat === 0) die('no claim observed');
}

// ---- runs：创建 agent session + M 条消息（每条触发一次 run），输出 runId + 客户端提交时刻 ----
async function runs() {
  const token = await login();
  const projectId = args.project; if (!projectId) die('--project required');
  const count = Number(args.count || 10);
  const label = args.label || 'run';
  const out = args.out || `data/runs-${label}.csv`;
  ensureDir(out);

  const sr = await api(`/api/v1/projects/${projectId}/agent/sessions`, {
    method: 'POST', token, json: { title: `bench-${label}-${Date.now()}` },
  });
  if (sr.payload?.code !== 'SUCCESS') die('create session failed: ' + JSON.stringify(sr.payload)?.slice(0, 400));
  const sessionId = sr.payload.data.id;

  const lines = ['seq,runId,sessionId,submitDoneMs,respCode,claimedMs,claimLatencyMs,terminalMs,terminalStatus'];
  let runIds = [];
  const terminal = new Set(['SUCCEEDED', 'FAILED', 'CANCELED', 'BUDGET_EXCEEDED', 'PAUSED']);
  for (let i = 0; i < count; i++) {
    const r = await api(`/api/v1/projects/${projectId}/agent/sessions/${sessionId}/messages`, {
      method: 'POST', token,
      json: { content: `bench run ${label} #${i}: 只做派发延迟测量，请直接回复“收到”。` },
    });
    const code = r.payload?.code ?? ('HTTP' + r.status);
    const runId = r.payload?.data?.runId || r.payload?.data?.id || '';
    let claimedMs = '', claimLatencyMs = '', terminalMs = '', terminalStatus = '';
    if (runId && args['serial-wait']) {
      // 串行模式：等本轮 run 离开 QUEUED（claim 完成）记录触发时延，再等终态，
      // 避免上一轮占满 worker 执行器导致下一轮 claim 排队（时延被队列等待污染）。
      const deadline = Date.now() + Number(args.waitTimeout || 120) * 1000;
      while (Date.now() < deadline) {
        const q = await api(`/api/v1/projects/${projectId}/agent/runs/${runId}`, { token });
        const st = q.payload?.data?.status;
        if (st && st !== 'QUEUED') { claimedMs = Date.now(); claimLatencyMs = claimedMs - r.doneMs; break; }
        if (!claimedMs && st && terminal.has(st)) { claimedMs = Date.now(); claimLatencyMs = claimedMs - r.doneMs; }
        await new Promise(s => setTimeout(s, 50));
      }
      const deadline2 = Date.now() + Number(args.waitTimeout || 120) * 1000;
      while (Date.now() < deadline2) {
        const q = await api(`/api/v1/projects/${projectId}/agent/runs/${runId}`, { token });
        const st = q.payload?.data?.status;
        if (st && terminal.has(st)) { terminalMs = Date.now(); terminalStatus = st; break; }
        await new Promise(s => setTimeout(s, 200));
      }
    }
    lines.push([i, runId, sessionId, r.doneMs, code, claimedMs, claimLatencyMs, terminalMs, terminalStatus].join(','));
    if (runId) runIds.push(runId);
    // 若失败，打印首条错误后停止，避免刷屏
    if (!runId && i === 0) {
      console.error('first message failed: ' + JSON.stringify(r.payload)?.slice(0, 400));
      break;
    }
  }
  fs.writeFileSync(out, lines.join('\n') + '\n');
  console.log(`runsSubmitted=${runIds.length}/${count} out=${out}`);
}

// ---- watch：恢复观测。轮询项目文档列表，记录每个目标文档首次离开 UPLOADED 的时刻 ----
// 用法：node bench.mjs watch --env .env --project <uuid> --label recovery --out data/watch.csv --timeout 120
// 同时输出健康时刻（watcher 首次成功拿到响应视为后端已恢复服务）。
async function watch() {
  const projectId = args.project; if (!projectId) die('--project required');
  const label = args.label || 'watch';
  const out = args.out || `data/watch-${label}.csv`;
  ensureDir(out);
  const firstSeen = new Map();
  const deadline = Date.now() + Number(args.timeout || 120) * 1000;
  let healthyAt = '';
  let token = '';
  while (Date.now() < deadline) {
    if (!token) {
      // 后端进程被 kill 后 API 不可用：登录循环重试，首次登录成功即视为"恢复服务"时刻
      const l = await api('/api/v1/auth/login', {
        method: 'POST', json: { username: ENV.DEMO_OWNER_USERNAME, password: ENV.DEMO_OWNER_PASSWORD },
      }).catch(() => null);
      if (l?.payload?.code === 'SUCCESS') { token = l.payload.data.accessToken; healthyAt = Date.now(); console.log(`backendHealthyAt=${healthyAt}`); }
      else { await new Promise(s => setTimeout(s, Number(args.interval || 250))); continue; }
    }
    const r = await api(`/api/v1/projects/${projectId}/documents?page=0&size=200`, { token }).catch(() => null);
    if (r && r.payload?.code === 'SUCCESS') {
      if (!healthyAt) { healthyAt = Date.now(); console.log(`backendHealthyAt=${healthyAt}`); }
      for (const d of (r.payload.data?.content || r.payload.data || [])) {
        if (!firstSeen.has(d.id) && d.status && d.status !== 'UPLOADED') firstSeen.set(d.id, { ts: Date.now(), status: d.status });
      }
    }
    await new Promise(s => setTimeout(s, Number(args.interval || 250)));
  }
  const lines = ['docId,healthyAtMs,firstSeenMs,firstSeenStatus,recoveryMs,label'];
  for (const [id, f] of firstSeen) {
    lines.push([id, healthyAt, f.ts, f.status, f.ts - healthyAt, label].join(','));
  }
  fs.writeFileSync(out, lines.join('\n') + '\n');
  console.log(`watched=${firstSeen.size} healthyAt=${healthyAt} out=${out}`);
}

const handlers = { setup, docs, runs, watch };
const fn = handlers[process.argv[2]];
if (!fn) die('usage: bench.mjs <setup|docs|runs> [options]');
await fn();
