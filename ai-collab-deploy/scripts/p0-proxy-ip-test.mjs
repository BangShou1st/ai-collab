// 仅在一次性容器里运行：从两个实际容器 IP 经过反代及直连后端验证限流。
import assert from 'node:assert/strict'
import http from 'node:http'
import https from 'node:https'
const mode = process.env.TEST_MODE
const registration = mode === 'register-a' || mode === 'register-b'
const count = mode === 'proxy-a' ? 31 : mode === 'register-a' ? 11 : 1
const origin = process.env.TEST_ORIGIN
const direct = mode === 'direct'
const send = i => new Promise((resolve, reject) => {
  const body = JSON.stringify(registration
    ? { username: 'p0owner', displayName: 'duplicate', password: 'synthetic-only' }
    : { username: 'p0-rate-limit', password: 'synthetic-only' })
  const req = (direct ? http : https).request({
    hostname: direct ? 'app-edge' : '172.30.173.2', port: direct ? 8080 : 443,
    path: registration ? '/api/v1/auth/register' : '/api/v1/auth/login', method: 'POST', rejectUnauthorized: false,
    headers: { Origin: origin, 'Content-Type': 'application/json',
      'Content-Length': Buffer.byteLength(body), 'X-Forwarded-For': `198.51.100.${(i % 200) + 1}`,
      'X-Real-IP': '203.0.113.11', Forwarded: 'for=203.0.113.11;proto=http',
      'X-Forwarded-Proto': 'http' },
  }, res => { res.resume(); res.on('end', () => resolve(res.statusCode)) })
  req.on('error', reject); req.end(body)
})
const codes = []
for (let i = 0; i < count; i++) codes.push(await send(i))
if (mode === 'proxy-a') {
  assert.ok(codes.slice(0, 30).every(code => code === 401), `first 30 statuses: ${codes.join(',')}`)
  assert.equal(codes[30], 429)
} else if (mode === 'proxy-b') assert.equal(codes[0], 401)
else if (mode === 'direct') assert.equal(codes[0], 429)
else if (mode === 'register-a') {
  assert.ok(codes.slice(0, 10).every(code => code === 409), `first 10 statuses: ${codes.join(',')}`)
  assert.equal(codes[10], 429)
} else if (mode === 'register-b') assert.equal(codes[0], 409)
else throw new Error('invalid test mode')
console.log(`${mode}: actual HTTP rate-limit boundary verified`)
