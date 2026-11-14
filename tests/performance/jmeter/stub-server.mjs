#!/usr/bin/env node
/**
 * Minimal OpenAI-compatible stub for local AI-path load tests.
 * It never calls a real model. The fixed delay is deliberate so gateway overhead
 * can be separated from provider time in the JMeter JTL.
 */
import http from 'node:http';

const args = process.argv.slice(2);
const argValue = (name, fallback) => {
  const index = args.indexOf(name);
  return index >= 0 && args[index + 1] ? args[index + 1] : fallback;
};

const port = Number(argValue('--port', process.env.STUB_PORT ?? '18080'));
const delayMs = Number(argValue('--delay-ms', process.env.STUB_DELAY_MS ?? '200'));
const host = argValue('--host', '127.0.0.1');

const responseFor = (model) => ({
  id: 'chatcmpl-perf-stub',
  object: 'chat.completion',
  created: Math.floor(Date.now() / 1000),
  model,
  choices: [{
    index: 0,
    message: { role: 'assistant', content: '{"summary":"stub-ok"}' },
    finish_reason: 'stop',
  }],
  usage: { prompt_tokens: 32, completion_tokens: 8, total_tokens: 40 },
});

const json = (res, status, body) => {
  const text = JSON.stringify(body);
  res.writeHead(status, {
    'Content-Type': 'application/json',
    'Content-Length': Buffer.byteLength(text),
    'Cache-Control': 'no-store',
  });
  res.end(text);
};

const readBody = (req) => new Promise((resolve, reject) => {
  const chunks = [];
  req.on('data', (chunk) => chunks.push(chunk));
  req.on('end', () => resolve(Buffer.concat(chunks).toString('utf8')));
  req.on('error', reject);
});

const streamResponse = async (res, model) => {
  res.writeHead(200, {
    'Content-Type': 'text/event-stream',
    'Cache-Control': 'no-store',
    Connection: 'keep-alive',
  });
  const chunks = ['{"summary":', '"stub-ok"}'];
  for (const chunk of chunks) {
    const event = {
      id: 'chatcmpl-perf-stub',
      object: 'chat.completion.chunk',
      created: Math.floor(Date.now() / 1000),
      model,
      choices: [{ index: 0, delta: { content: chunk }, finish_reason: null }],
    };
    res.write(`data: ${JSON.stringify(event)}\n\n`);
    await new Promise((resolve) => setTimeout(resolve, 5));
  }
  res.write(`data: ${JSON.stringify({
    id: 'chatcmpl-perf-stub',
    object: 'chat.completion.chunk',
    created: Math.floor(Date.now() / 1000),
    model,
    choices: [{ index: 0, delta: {}, finish_reason: 'stop' }],
    usage: { prompt_tokens: 32, completion_tokens: 8, total_tokens: 40 },
  })}\n\n`);
  res.write('data: [DONE]\n\n');
  res.end();
};

const server = http.createServer(async (req, res) => {
  try {
    if (req.method === 'GET' && req.url === '/health') {
      json(res, 200, { status: 'UP', delayMs });
      return;
    }
    if (req.method === 'GET' && req.url === '/v1/models') {
      json(res, 200, { object: 'list', data: [{ id: 'perf-stub', object: 'model' }] });
      return;
    }
    if (req.method !== 'POST' || req.url !== '/v1/chat/completions') {
      json(res, 404, { error: { message: 'not found', type: 'not_found' } });
      return;
    }

    const raw = await readBody(req);
    let body = {};
    try { body = JSON.parse(raw); } catch { /* the downstream contract check will fail */ }
    const model = typeof body.model === 'string' ? body.model : 'perf-stub';
    if (body.stream === true) {
      await streamResponse(res, model);
      return;
    }
    await new Promise((resolve) => setTimeout(resolve, delayMs));
    json(res, 200, responseFor(model));
  } catch (error) {
    json(res, 500, { error: { message: 'stub failure', type: 'stub_error' } });
  }
});

server.listen(port, host, () => {
  console.log(`perf stub listening on http://${host}:${port} delay=${delayMs}ms`);
});

const shutdown = () => server.close(() => process.exit(0));
process.on('SIGINT', shutdown);
process.on('SIGTERM', shutdown);
