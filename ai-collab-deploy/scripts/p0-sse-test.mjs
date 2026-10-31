// 使用预置合成事件，验证实际应用经反代发送首帧及 Last-Event-ID 重放。
import assert from 'node:assert/strict'
import https from 'node:https'
const port = Number(process.env.TEST_PORT)
const origin = `https://p0.example.test:${port}`
const options = { hostname: '127.0.0.1', servername: 'p0.example.test', port, rejectUnauthorized: false }
const login = await new Promise((resolve, reject) => {
  const body = JSON.stringify({ username: 'p0owner', password: 'synthetic-p0-password' })
  const req = https.request({ ...options, path: '/api/v1/auth/login', method: 'POST',
    headers: { Host: `p0.example.test:${port}`, Origin: origin, 'Content-Type': 'application/json',
      'Content-Length': Buffer.byteLength(body) } }, res => {
    let text = ''; res.on('data', b => { text += b }); res.on('end', () => {
      try { assert.equal(res.statusCode, 200); resolve(JSON.parse(text).data.accessToken) } catch (e) { reject(e) }
    })
  }); req.on('error', reject); req.end(body)
})
const stream = lastId => new Promise((resolve, reject) => {
  let settled = false; let data = ''
  const timer = setTimeout(() => { if (!settled) { settled = true; req.destroy(); reject(new Error('SSE 首帧超时')) } }, 7000)
  const req = https.request({ ...options, path: '/api/v1/projects/11111111-1111-4111-8111-111111111111/agent/runs/77777777-7777-4777-8777-777777777777/events',
    headers: { Host: `p0.example.test:${port}`, Origin: origin, Authorization: `Bearer ${login}`,
      Accept: 'text/event-stream', ...(lastId ? { 'Last-Event-ID': lastId } : {}) } }, res => {
    if (res.statusCode !== 200) { settled = true; clearTimeout(timer); res.destroy(); reject(new Error(`SSE HTTP ${res.statusCode}`)); return }
    res.on('data', chunk => {
      data += chunk.toString('utf8')
      if (/id:\s*2(?:\r?\n)/.test(data)) {
        settled = true; clearTimeout(timer); res.destroy(); resolve(data)
      }
    })
    res.on('error', err => { if (!settled) { settled = true; clearTimeout(timer); reject(err) } })
  })
  req.on('error', err => { if (!settled) { settled = true; clearTimeout(timer); reject(err) } }); req.end()
})
const initial = await stream(undefined)
assert.match(initial, /id:\s*1(?:\r?\n)/)
const resumed = await stream('1')
assert.doesNotMatch(resumed, /id:\s*1(?:\r?\n)/)
assert.match(resumed, /id:\s*2(?:\r?\n)/)
console.log('actual SSE first frame, client abort and Last-Event-ID replay: verified')
