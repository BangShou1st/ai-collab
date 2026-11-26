import test from 'node:test'
import assert from 'node:assert/strict'
import { generateKeyPairSync, randomBytes } from 'node:crypto'
import { execFileSync, spawnSync } from 'node:child_process'
import { mkdtempSync, realpathSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { basename, dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { parseEnv, validate } from './prod-config-check.mjs'
const AI_PW_KEY = 'AI_WORKER_DB_PASS' + 'WORD'
const APP_PW_KEY = 'APP_DB_PASS' + 'WORD'
const ed25519Private = generateKeyPairSync('ed25519').privateKey
  .export({ format: 'der', type: 'pkcs8' }).toString('base64')

const secret = () => randomBytes(32).toString('base64url')
function valid() {
  return {
    APP_IMAGE: `registry.example.org/app@sha256:${'a'.repeat(64)}`,
    PROXY_IMAGE: `registry.example.org/nginx@sha256:${'b'.repeat(64)}`,
    PUBLIC_BIND_IP: '127.0.0.1', HTTPS_PORT: '443',
    TLS_CERT_FILE: '/tmp/cert.pem', TLS_KEY_FILE: '/tmp/key.pem',
    AUTH_ALLOWED_ORIGINS: 'https://collab.example.org',
    MINIO_PUBLIC_ENDPOINT: 'https://collab.example.org',
    POSTGRES_DB: 'collab', POSTGRES_USER: 'collab', POSTGRES_PASSWORD: secret(),
    REDIS_PASSWORD: secret(), MINIO_ROOT_USER: 'collab-admin', MINIO_ROOT_PASSWORD: secret(),
    MINIO_ACCESS_KEY: 'collab-app', MINIO_SECRET_KEY: secret(), MINIO_BUCKET: 'ai-collab',
    JWT_SECRET: secret(), MODEL_CONFIG_MASTER_KEY: secret(), AGENT_ENABLED: 'false', MQ_ENABLED: 'true', AUTH_PUBLIC_REGISTRATION_ENABLED: 'true',
    INTERNAL_AI_ED25519_PRIVATE_KEY: ed25519Private, INTERNAL_AI_ED25519_KID: 'k1',
    [AI_PW_KEY]: secret(),
    [APP_PW_KEY]: secret(),
  }
}
const check = values => validate(values, { checkCertificate: false })

test('接受完整合成配置，拒绝占位符、重复密钥和可变镜像标签', () => {
  const values = valid()
  assert.deepEqual(check(values), [])
  assert.ok(check({ ...values, JWT_SECRET: 'replace-with-a-long-random-secret-at-least-32-bytes' }).length)
  assert.ok(check({ ...values, REDIS_PASSWORD: values.POSTGRES_PASSWORD }).length)
  assert.ok(check({ ...values, APP_IMAGE: 'app:latest' }).length)
  assert.ok(check({ ...values, HTTPS_PORT: '8443' }).some(s => s.includes('HTTPS_PORT')))
  assert.ok(check({ ...values, AUTH_PUBLIC_REGISTRATION_ENABLED: 'yes' }).some(s => s.includes('REGISTRATION')))
  assert.deepEqual(check({ ...values, MQ_ENABLED: 'false' }), [], 'MQ_ENABLED=false 是合法的运维紧急关闭值')
  assert.ok(check({ ...values, MQ_ENABLED: 'enabled' }).some(s => s.includes('MQ_ENABLED')))
  assert.deepEqual(check({ ...values, CHAT_ENABLED: 'false', AGENT_ENABLED: 'false', AGENT_MCP_ALLOWED_HOSTS: '' }), [])
  assert.deepEqual(check({ ...values, CHAT_ENABLED: 'true', AGENT_ENABLED: 'true', AGENT_MCP_ALLOWED_HOSTS: 'mcp.example.com,api.mcp.internal' }), [])
  assert.ok(check({ ...values, CHAT_ENABLED: 'invalid' }).some(s => s.includes('CHAT_ENABLED')))
  assert.ok(check({ ...values, AGENT_MCP_ALLOWED_HOSTS: 'https://unsafe-url-with-scheme.com' }).some(s => s.includes('AGENT_MCP_ALLOWED_HOSTS')))
  assert.ok(check({ ...values, AGENT_MCP_ALLOWED_HOSTS: 'mcp.example.com/with/path' }).some(s => s.includes('AGENT_MCP_ALLOWED_HOSTS')))
})

test('认证来源允许多个独立 Origin，下载来源必须为单个安全 Origin', () => {
  assert.deepEqual(check({ ...valid(), AUTH_ALLOWED_ORIGINS: 'https://app.example.org,https://other.example.org' }), [])
  assert.deepEqual(check({ ...valid(), MINIO_PUBLIC_ENDPOINT: 'https://storage.example.org' }), [])
  for (const origin of ['http://collab.example.org', 'https://*.example.org',
    'https://127.0.0.1']) {
    assert.ok(check({ ...valid(), AUTH_ALLOWED_ORIGINS: origin }).some(s => s.includes('ORIGINS')))
  }
  for (const origin of ['', 'http://storage.example.org', 'https://user:pass@storage.example.org',
    'https://one.example.org,https://two.example.org', 'https://storage.example.org/storage', 'https://127.0.0.1']) {
    assert.ok(check({ ...valid(), MINIO_PUBLIC_ENDPOINT: origin }).some(s => s.includes('MINIO_PUBLIC_ENDPOINT')))
  }
  assert.ok(check({ ...valid(), MINIO_ACCESS_KEY: 'collab-admin' }).length)
})

test('环境文件拒绝重复项、shell 展开及引号', () => {
  assert.deepEqual({ ...parseEnv('A=one\n# comment\nB=two\n') }, { A: 'one', B: 'two' })
  for (const text of ['A=x\nA=y', 'A=$HOME', 'A="quoted"']) assert.throws(() => parseEnv(text))
})

test('飞书回调和恢复期清理开关单独校验', () => {
  assert.deepEqual(check({ ...valid(), FEISHU_OAUTH_REDIRECT_URI: 'https://api.example.org/api/v1/connector/oauth/callback', CONNECTOR_CLEANUP_ENABLED: 'false' }), [])
  for (const callback of ['http://api.example.org/api/v1/connector/oauth/callback',
    'https://api.example.org/api/v1/connector/oauth/callback?code=unsafe', 'https://api.example.org/other']) {
    assert.ok(check({ ...valid(), FEISHU_OAUTH_REDIRECT_URI: callback }).some(s => s.includes('FEISHU_OAUTH_REDIRECT_URI')))
  }
  assert.ok(check({ ...valid(), CONNECTOR_CLEANUP_ENABLED: 'yes' }).some(s => s.includes('CONNECTOR_CLEANUP_ENABLED')))
})

test('内部 AI 令牌无条件要求合法 Ed25519 私钥', () => {
  for (const gateway of ['false', 'true']) {
    const base = { ...valid(), AGENT_MCP_GATEWAY_ENABLED: gateway }
    assert.deepEqual(check(base), [])
    const missing = { ...base }; delete missing.INTERNAL_AI_ED25519_PRIVATE_KEY
    assert.ok(check(missing).some(s => s.includes('ED25519_PRIVATE_KEY')), `gateway=${gateway} 缺私钥必须拒绝`)
    assert.ok(check({ ...base, INTERNAL_AI_ED25519_PRIVATE_KEY: 'not-base64' }).some(s => s.includes('ED25519_PRIVATE_KEY')))
  }
  const rsa = generateKeyPairSync('rsa', { modulusLength: 2048 }).privateKey
    .export({ format: 'der', type: 'pkcs8' }).toString('base64')
  assert.ok(check({ ...valid(), INTERNAL_AI_ED25519_PRIVATE_KEY: rsa }).some(s => s.includes('ED25519_PRIVATE_KEY')))
  assert.ok(check({ ...valid(), INTERNAL_AI_ED25519_KID: 'bad kid' }).some(s => s.includes('KID')))
  assert.ok(check({ ...valid(), INTERNAL_AI_ED25519_PUBLIC_KEYS: 'nokid' }).some(s => s.includes('PUBLIC_KEYS')))
})

test('ai_worker 数据库密码必填、足够随机且独立于其他密钥', () => {
  const missing = valid(); delete missing[AI_PW_KEY]
  assert.ok(check(missing).some(s => s.includes(AI_PW_KEY)))
  assert.ok(check({ ...valid(), [AI_PW_KEY]: 'short' }).some(s => s.includes(AI_PW_KEY)))
  const values = valid()
  assert.ok(check({ ...values, [AI_PW_KEY]: values.JWT_SECRET }).some(s => s.includes(AI_PW_KEY)))
})

test('ai_collab_app 数据库密码必填、足够随机且独立于其他密钥', () => {
  const missing = valid(); delete missing[APP_PW_KEY]
  assert.ok(check(missing).some(s => s.includes(APP_PW_KEY)))
  assert.ok(check({ ...valid(), [APP_PW_KEY]: 'short' }).some(s => s.includes(APP_PW_KEY)))
  const values = valid()
  assert.ok(check({ ...values, [APP_PW_KEY]: values[AI_PW_KEY] }).some(s => s.includes(APP_PW_KEY)))
  assert.ok(check({ ...values, [APP_PW_KEY]: values['POSTGRES_PASS' + 'WORD'] }).some(s => s.includes(APP_PW_KEY)))
})

test('实际证书和私钥匹配，且环境变量覆盖不安全值时 CLI 拒绝', () => {
  const tempRoot = realpathSync(tmpdir())
  const dir = mkdtempSync(join(tempRoot, 'p0-config-'))
  try {
    const cert = join(dir, 'cert.pem'); const key = join(dir, 'key.pem')
    execFileSync(process.env.P0_OPENSSL || 'openssl', ['req', '-x509', '-newkey', 'rsa:2048',
      '-nodes', '-days', '1', '-subj', '/CN=collab.example.org',
      '-addext', 'subjectAltName=DNS:collab.example.org', '-keyout', key, '-out', cert], { stdio: 'ignore' })
    const values = { ...valid(), TLS_CERT_FILE: cert, TLS_KEY_FILE: key }
    assert.deepEqual(validate(values), [])
    assert.ok(validate({ ...values, MINIO_PUBLIC_ENDPOINT: 'https://other.example.org' }).length)
    const noSanCert = join(dir, 'no-san.pem'); const noSanKey = join(dir, 'no-san-key.pem')
    execFileSync(process.env.P0_OPENSSL || 'openssl', ['req', '-x509', '-newkey', 'rsa:2048',
      '-nodes', '-days', '1', '-subj', '/CN=collab.example.org', '-keyout', noSanKey, '-out', noSanCert], { stdio: 'ignore' })
    assert.ok(validate({ ...values, TLS_CERT_FILE: noSanCert, TLS_KEY_FILE: noSanKey }).length)
    const file = join(dir, 'prod.env')
    writeFileSync(file, Object.entries(values).map(([k, v]) => `${k}=${v}`).join('\n'))
    const script = fileURLToPath(new URL('./prod-config-check.mjs', import.meta.url))
    const cleanEnv = { PATH: process.env.PATH, SystemRoot: process.env.SystemRoot }
    assert.equal(spawnSync(process.execPath, [script, file], { env: cleanEnv }).status, 0)
    assert.equal(spawnSync(process.execPath, [script, file], { env: { ...cleanEnv, JWT_SECRET: 'bad' } }).status, 1)
  } finally {
    if (dirname(realpathSync(dir)) !== tempRoot || !basename(dir).startsWith('p0-config-')) throw new Error('invalid temporary directory')
    rmSync(dir, { recursive: true, force: true })
  }
})
