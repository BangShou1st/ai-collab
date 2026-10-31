// 测试数据与令牌仅驻留本进程；不输出响应体、Cookie 或签名 URL。
import assert from 'node:assert/strict'
import https from 'node:https'
import { readFileSync } from 'node:fs'
import { createHash, createHmac } from 'node:crypto'
import { spawnSync } from 'node:child_process'
const port = Number(process.env.TEST_PORT)
const ca = readFileSync(process.env.TEST_CERT)
const origin = `https://p0.example.test:${port}`
const p1 = '11111111-1111-4111-8111-111111111111'
const p2 = '22222222-2222-4222-8222-222222222222'
const member = '44444444-4444-4444-8444-444444444444'
const doc = '55555555-5555-4555-8555-555555555555'
function request(path, { method = 'GET', body, token, cookie, source = origin, headers = {} } = {}) {
  return new Promise((resolve, reject) => {
    const data = body === undefined ? undefined : JSON.stringify(body)
    const req = https.request({ hostname: '127.0.0.1', servername: 'p0.example.test', port, path, method, ca,
      headers: { Host: `p0.example.test:${port}`, Origin: source, ...headers,
        ...(token ? { Authorization: `Bearer ${token}` } : {}),
        ...(cookie ? { Cookie: cookie } : {}),
        ...(data ? { 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(data) } : {}) },
    }, res => { const chunks = []; res.on('data', c => chunks.push(c)); res.on('end', () => {
      const text = Buffer.concat(chunks).toString('utf8')
      let json; try { json = JSON.parse(text) } catch { json = undefined }
      resolve({ status: res.statusCode, headers: res.headers, text, json })
    }) })
    req.on('error', reject); req.end(data)
  })
}
assert.equal((await request('/actuator/env')).status, 404)
assert.equal((await request('/api/v1/projects')).status, 401)
const registrationPolicy = await request('/api/v1/auth/registration-policy')
assert.equal(registrationPolicy.status, 200)
assert.equal(registrationPolicy.json.data.enabled, true)
const registered = await request('/api/v1/auth/register', { method: 'POST',
  body: { username: 'p0newuser', displayName: 'P0 new user', password: 'synthetic-p0-password' } })
assert.equal(registered.status, 200)
assert.equal(registered.json.data.user.systemAdmin, false)
assert.equal((await request('/api/v1/admin/users', { token: registered.json.data.accessToken })).status, 403)
assert.equal((await request('/api/v1/auth/login', { method: 'POST', source: 'https://evil.example',
  body: { username: 'p0owner', password: 'synthetic-p0-password' } })).status, 403)
const login = async username => request('/api/v1/auth/login', { method: 'POST',
  body: { username, password: 'synthetic-p0-password' }, headers: { 'X-Request-Id': 'forged-id' } })
const owner = await login('p0owner')
assert.equal(owner.status, 200)
assert.notEqual(owner.headers['x-request-id'], 'forged-id')
const ownerCookie = owner.headers['set-cookie'][0]
assert.match(ownerCookie, /HttpOnly/i); assert.match(ownerCookie, /Secure/i); assert.match(ownerCookie, /SameSite=Strict/i)
const ownerToken = owner.json.data.accessToken
const memberLogin = await login('p0member')
assert.equal(memberLogin.status, 200)
const memberToken = memberLogin.json.data.accessToken
assert.equal((await request(`/api/v1/projects/${p1}`, { token: memberToken })).status, 200)
assert.equal((await request(`/api/v1/projects/${p1}`, { method: 'DELETE', token: memberToken })).status, 403)
const cross = await request(`/api/v1/projects/${p2}`, { token: memberToken })
assert.ok([403, 404].includes(cross.status))
const signed = await request(`/api/v1/projects/${p1}/documents/${doc}/download-url`, { token: ownerToken })
assert.equal(signed.status, 200)
const url = new URL(signed.json.data.url)
assert.equal(url.origin, origin); assert.ok(url.pathname.startsWith('/storage/'))
const download = await request(url.pathname + url.search)
assert.equal(download.status, 200); assert.equal(download.text, 'p0-synthetic-object')
assert.match(download.headers['content-disposition'] || '', /^attachment/i)
assert.equal(createHash('sha256').update(download.text).digest('hex'),
  createHash('sha256').update('p0-synthetic-object').digest('hex'))
// 回归防护：生产 Nginx 必须丢弃客户端伪造的转发链，不受恶意 X-Forwarded-Host 干扰
const forgedForwardedHostDownload = await request(url.pathname + url.search, {
  headers: { 'X-Forwarded-Host': 'evil.attacker.test' }
})
assert.equal(forgedForwardedHostDownload.status, 200)
assert.equal(forgedForwardedHostDownload.text, 'p0-synthetic-object')
const forgedHostDownload = await request(url.pathname + url.search, {
  headers: { Host: 'evil.attacker.test' }
})
assert.equal(forgedHostDownload.status, 403)
assert.ok([403, 404].includes((await request(`/api/v1/projects/${p2}/documents/${doc}/download-url`, { token: memberToken })).status))
const anonymous = await request(url.pathname)
assert.equal(anonymous.status, 403)
assert.match(anonymous.headers['content-type'] || '', /xml/i)
const tampered = new URL(url)
const signature = tampered.searchParams.get('X-Amz-Signature')
assert.ok(signature)
tampered.searchParams.set('X-Amz-Signature', `${signature[0] === '0' ? '1' : '0'}${signature.slice(1)}`)
assert.equal((await request(tampered.pathname + tampered.search)).status, 403)
assert.equal((await request(`${url.pathname}x${url.search}`)).status, 403)
// 以隔离环境的合成密钥构造过去的有效签名，验证过期本身而非签名损坏。
const expiredUrl = new URL(url)
const past = new Date(Date.now() - 3600_000).toISOString().replace(/[:-]|\.\d{3}/g, '')
const date = past.slice(0, 8)
expiredUrl.searchParams.set('X-Amz-Date', past)
expiredUrl.searchParams.set('X-Amz-Expires', '60')
const credential = expiredUrl.searchParams.get('X-Amz-Credential').split('/')
credential[1] = date
expiredUrl.searchParams.set('X-Amz-Credential', credential.join('/'))
expiredUrl.searchParams.delete('X-Amz-Signature')
const encodeQuery = value => encodeURIComponent(value).replace(/[!'()*]/g, ch => `%${ch.charCodeAt(0).toString(16).toUpperCase()}`)
const canonicalQuery = [...expiredUrl.searchParams].map(([key, value]) => [encodeQuery(key), encodeQuery(value)])
  .sort(([a, av], [b, bv]) => a < b ? -1 : a > b ? 1 : av < bv ? -1 : av > bv ? 1 : 0)
  .map(pair => pair.join('=')).join('&')
const digest = value => createHash('sha256').update(value).digest('hex')
const hmac = (key, value) => createHmac('sha256', key).update(value).digest()
const canonical = `GET\n${expiredUrl.pathname.slice('/storage'.length)}\n${canonicalQuery}\nhost:${expiredUrl.host}\n\nhost\nUNSIGNED-PAYLOAD`
const scope = `${date}/us-east-1/s3/aws4_request`
const stringToSign = `AWS4-HMAC-SHA256\n${past}\n${scope}\n${digest(canonical)}`
const signingKey = hmac(hmac(hmac(hmac(`AWS4${process.env.MINIO_SECRET_KEY}`, date), 'us-east-1'), 's3'), 'aws4_request')
expiredUrl.searchParams.set('X-Amz-Signature', createHmac('sha256', signingKey).update(stringToSign).digest('hex'))
const expiredDownload = await request(expiredUrl.pathname + expiredUrl.search)
assert.equal(expiredDownload.status, 403)
assert.match(expiredDownload.text, /Expired|expired/i)
const encodedLink = await request(`/api/v1/projects/${p1}/documents/88888888-8888-4888-8888-888888888888/download-url`, { token: ownerToken })
assert.equal(encodedLink.status, 200)
const encodedUrl = new URL(encodedLink.json.data.url)
const encodedDownload = await request(encodedUrl.pathname + encodedUrl.search)
assert.equal(encodedDownload.status, 200); assert.equal(encodedDownload.text, 'p0-encoded-object')
const uploaded = await new Promise((resolve, reject) => {
  const boundary = 'p0-synthetic-boundary'
  const body = `--${boundary}\r\nContent-Disposition: form-data; name="file"; filename="synthetic.txt"\r\nContent-Type: text/plain\r\n\r\np0-synthetic-upload\r\n--${boundary}--\r\n`
  const req = https.request({ hostname: '127.0.0.1', servername: 'p0.example.test', port, ca,
    path: `/api/v1/projects/${p1}/documents`, method: 'POST', headers: { Host: `p0.example.test:${port}`,
      Origin: origin, Authorization: `Bearer ${ownerToken}`, 'Content-Type': `multipart/form-data; boundary=${boundary}`,
      'Content-Length': Buffer.byteLength(body) } }, res => {
    let text = ''; res.on('data', b => { text += b }); res.on('end', () => {
      try { assert.equal(res.statusCode, 202); resolve(JSON.parse(text).data) } catch (e) { reject(e) }
    })
  }); req.on('error', reject); req.end(body)
})
assert.ok(uploaded.id)
const uploadedLink = await request(`/api/v1/projects/${p1}/documents/${uploaded.id}/download-url`, { token: ownerToken })
assert.equal(uploadedLink.status, 200)
const uploadedUrl = new URL(uploadedLink.json.data.url)
assert.equal((await request(uploadedUrl.pathname + uploadedUrl.search)).text, 'p0-synthetic-upload')
assert.equal((await request(`/api/v1/projects/${p1}/members/${member}`, { method: 'DELETE', token: ownerToken })).status, 204)
assert.ok([403, 404].includes((await request(`/api/v1/projects/${p1}`, { token: memberToken })).status))
const refreshed = await request('/api/v1/auth/refresh', { method: 'POST', cookie: ownerCookie.split(';')[0] })
assert.equal(refreshed.status, 200)
const refreshedCookie = refreshed.headers['set-cookie'][0].split(';')[0]
assert.equal((await request('/api/v1/auth/logout', { method: 'POST', cookie: refreshedCookie })).status, 200)
assert.equal((await request('/api/v1/auth/refresh', { method: 'POST', cookie: refreshedCookie })).status, 401)
assert.equal((await request('/api/v1/auth/me', { token: `${ownerToken}broken` })).status, 401)
// 使用隔离测试的签名密钥生成真实过期 JWT；令牌仅驻留内存。
const encode = value => Buffer.from(JSON.stringify(value)).toString('base64url')
const now = Math.floor(Date.now() / 1000)
const unsigned = `${encode({ alg: 'HS256', typ: 'JWT' })}.${encode({ sub: '33333333-3333-4333-8333-333333333333', iat: now - 600, exp: now - 300 })}`
assert.ok(process.env.JWT_SECRET)
const expired = `${unsigned}.${createHmac('sha256', process.env.JWT_SECRET).update(unsigned).digest('base64url')}`
assert.equal((await request('/api/v1/auth/me', { token: expired })).status, 401)
assert.match(process.env.TEST_PROJECT || '', /^p0iso[0-9]+$/)
const authSecrets = [ownerToken, memberToken, registered.json.data.accessToken,
  ownerCookie.split(';')[0].split('=').slice(1).join('='), refreshedCookie.split('=').slice(1).join('=')]
for (const role of ['app', 'proxy']) {
  const result = spawnSync('docker', ['logs', `${process.env.TEST_PROJECT}-${role}-1`], { encoding: 'utf8' })
  assert.equal(result.status, 0)
  const logs = result.stdout + result.stderr
  for (const secret of authSecrets) assert.ok(secret && !logs.includes(secret), '日志包含合成会话凭据')
}
console.log('HTTPS public registration, ordinary-user RBAC, auth, Cookie, Origin and document download: verified')
