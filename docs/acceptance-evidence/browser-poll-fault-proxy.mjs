// Isolated acceptance harness. Never points at the original application's port/database.
import http from 'node:http';
import fs from 'node:fs';
const control = process.argv[2];
const evidence = process.argv[3];
if (!control || !evidence) throw new Error('Provide private control JSON and sanitized log paths');
let stamp = '', settings = {}, armed = false, remaining = 0;
function reload() {
  const raw = fs.existsSync(control) ? fs.readFileSync(control, 'utf8').replace(/^\uFEFF/, '') : '{}';
  if (raw !== stamp) { stamp = raw; settings = JSON.parse(raw); remaining = settings.failures ?? 0; armed = !settings.afterPending; }
}
function record(value) { fs.appendFileSync(evidence, JSON.stringify({ at: new Date().toISOString(), ...value }) + '\n'); }
http.createServer((req, res) => {
  reload();
  const isPoll = req.method === 'GET' && /^\/api\/v1\/projects\/[^/]+\/agent\/sessions\/[^/]+\/planning-operations$/.test(req.url);
  const selected = isPoll && req.url.includes(settings.sessionId ?? '__not_armed__');
  if (selected && armed && remaining > 0) {
    remaining--; record({ path: req.url, status: 503, injected: true, remaining });
    res.writeHead(503, { 'content-type': 'application/json' });
    res.end(JSON.stringify({ code: 'ACCEPTANCE_POLL_FAILURE', message: 'Isolated acceptance request failure' }));
    return;
  }
  const upstream = http.request({ hostname: '127.0.0.1', port: 18080, method: req.method, path: req.url, headers: { ...req.headers, host: 'localhost:18080' } }, response => {
    res.writeHead(response.statusCode, response.headers);
    const parts = [];
    response.on('data', data => { if (isPoll) parts.push(data); res.write(data); });
    response.on('end', () => {
      if (isPoll) {
        let states = []; try { states = JSON.parse(Buffer.concat(parts).toString()).data?.map(o => o.status) ?? []; } catch {}
        record({ path: req.url, status: response.statusCode, injected: false, states });
        if (selected && states.some(s => ['ACCEPTED', 'SKELETON_GENERATING', 'DETAIL_GENERATING', 'REPAIRING'].includes(s))) armed = true;
      }
      res.end();
    });
  });
  upstream.on('error', () => { res.writeHead(502); res.end(); });
  req.pipe(upstream);
}).listen(18081, '127.0.0.1', () => console.log('Isolated polling-failure proxy ready on 18081 → 18080'));
