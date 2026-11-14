#!/usr/bin/env node
/**
 * Measures a local/mock OpenAI-compatible stub without Java or a real model.
 * Usage: node measure-stub.mjs --url http://127.0.0.1:18080 --requests 20 --concurrency 5
 */
const args = process.argv.slice(2);
const argValue = (name, fallback) => {
  const index = args.indexOf(name);
  return index >= 0 && args[index + 1] ? args[index + 1] : fallback;
};

const baseUrl = argValue('--url', 'http://127.0.0.1:18080').replace(/\/$/, '');
const total = Number(argValue('--requests', '20'));
const concurrency = Number(argValue('--concurrency', '1'));
const endpoint = baseUrl.endsWith('/v1/chat/completions') ? baseUrl : `${baseUrl}/v1/chat/completions`;

const percentile = (values, p) => {
  const sorted = [...values].sort((a, b) => a - b);
  const index = Math.min(sorted.length - 1, Math.ceil((p / 100) * sorted.length) - 1);
  return sorted[index];
};

const one = async () => {
  const started = performance.now();
  const response = await fetch(endpoint, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      model: 'perf-stub',
      messages: [{ role: 'user', content: 'stub latency probe' }],
      stream: false,
    }),
  });
  await response.arrayBuffer();
  if (!response.ok) throw new Error(`HTTP ${response.status}`);
  return performance.now() - started;
};

const results = [];
while (results.length < total) {
  const batch = Math.min(concurrency, total - results.length);
  const elapsed = await Promise.all(Array.from({ length: batch }, () => one()));
  results.push(...elapsed);
}

console.log(JSON.stringify({
  endpoint,
  requests: results.length,
  concurrency,
  p50Ms: Number(percentile(results, 50).toFixed(2)),
  p95Ms: Number(percentile(results, 95).toFixed(2)),
  p99Ms: Number(percentile(results, 99).toFixed(2)),
  maxMs: Number(Math.max(...results).toFixed(2)),
}));
