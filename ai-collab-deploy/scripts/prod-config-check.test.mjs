import test from 'node:test'
import assert from 'node:assert/strict'
import { randomBytes } from 'node:crypto'
import { execFileSync, spawnSync } from 'node:child_process'
import { mkdtempSync, realpathSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { basename, dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { parseEnv, validate } from './prod-config-check.mjs'

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
    JWT_SECRET: secret(), MODEL_CONFIG_MASTER_KEY: secret(), AGENT_ENABLED: 'false', AUTH_PUBLIC_REGISTRATION_ENABLED: 'true',
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
