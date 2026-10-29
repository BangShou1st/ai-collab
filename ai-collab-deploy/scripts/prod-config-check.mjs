#!/usr/bin/env node
// 仅校验，不打印任何配置值。环境变量与 docker compose 一样优先于 env 文件。
import { readFileSync } from 'node:fs'
import { isIP } from 'node:net'
import { isAbsolute } from 'node:path'
import { createPrivateKey, createPublicKey, X509Certificate } from 'node:crypto'
import { pathToFileURL } from 'node:url'

export function parseEnv(text) {
  const values = Object.create(null)
  for (const [index, raw] of text.split(/\r?\n/).entries()) {
    const line = raw.trim()
    if (!line || line.startsWith('#')) continue
    const match = /^([A-Z][A-Z0-9_]*)=([^\s'"$#]*)$/.exec(line)
    if (!match || Object.hasOwn(values, match[1])) {
      throw new Error(`环境文件第 ${index + 1} 行格式无效或重复`)
    }
    values[match[1]] = match[2]
  }
  return values
}

export function validate(values, { checkCertificate = true } = {}) {
  const errors = []
  const bad = (condition, message) => { if (condition) errors.push(message) }
  for (const name of ['APP_IMAGE', 'PROXY_IMAGE']) {
    bad(!/^[a-z0-9][a-z0-9._:/-]*@sha256:[a-f0-9]{64}$/.test(values[name] || ''),
      `${name} 必须是带 sha256 digest 的镜像`)
  }
  const secretNames = ['POSTGRES_PASSWORD', 'REDIS_PASSWORD', 'MINIO_ROOT_PASSWORD',
    'MINIO_SECRET_KEY', 'JWT_SECRET', 'MODEL_CONFIG_MASTER_KEY']
  for (const name of secretNames) {
    const value = values[name] || ''
    const min = name === 'JWT_SECRET' || name === 'MODEL_CONFIG_MASTER_KEY' ? 43 : 32
    bad(!/^[A-Za-z0-9_-]+$/.test(value) || value.length < min || new Set(value).size < 12 ||
      /change.?me|replace|placeholder|required/i.test(value), `${name} 未满足随机值格式要求`)
  }
  const secrets = secretNames.map(name => values[name]).filter(Boolean)
  bad(new Set(secrets).size !== secrets.length, '各服务密钥必须彼此不同')
  for (const name of ['POSTGRES_DB', 'POSTGRES_USER']) {
    bad(!/^[a-z][a-z0-9_]{0,62}$/.test(values[name] || ''), `${name} 格式无效`)
  }
  for (const name of ['MINIO_ROOT_USER', 'MINIO_ACCESS_KEY']) {
    bad(!/^[A-Za-z0-9_-]{3,20}$/.test(values[name] || ''), `${name} 格式无效`)
  }
  bad(values.MINIO_ROOT_USER === values.MINIO_ACCESS_KEY, 'MinIO 应用账号必须独立于管理员账号')
  bad(!/^[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]$/.test(values.MINIO_BUCKET || '') ||
    /\.\.|\.-|-\./.test(values.MINIO_BUCKET || '') || isIP(values.MINIO_BUCKET || '') !== 0,
  'MINIO_BUCKET 格式无效')
  bad(isIP(values.PUBLIC_BIND_IP || '0.0.0.0') !== 4, 'PUBLIC_BIND_IP 必须是明确的 IPv4 地址')
  const port = Number(values.HTTPS_PORT || '443')
  bad(port !== 443, '生产 HTTPS_PORT 必须为 443；其他端口需要重新设计和验证代理信任边界')
  bad(!['true', 'false', ''].includes(values.AGENT_ENABLED || ''), 'AGENT_ENABLED 必须是 true 或 false')
  bad(!['true', 'false'].includes(values.AUTH_PUBLIC_REGISTRATION_ENABLED || 'true'),
    'AUTH_PUBLIC_REGISTRATION_ENABLED 必须是 true 或 false')
  bad(!['true', 'false'].includes(values.CONNECTOR_CLEANUP_ENABLED || 'true'),
    'CONNECTOR_CLEANUP_ENABLED 必须是 true 或 false')

  const dnsName = /^(?=.{1,253}$)(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$/
  const parseOrigin = value => {
    const parsed = new URL(value)
    if (parsed.protocol !== 'https:' || parsed.origin !== value ||
        !dnsName.test(parsed.hostname) || isIP(parsed.hostname) !== 0) throw new Error()
    return parsed
  }
  try {
    const allowed = (values.AUTH_ALLOWED_ORIGINS || '').split(',')
    if (new Set(allowed).size !== allowed.length) throw new Error()
    allowed.forEach(parseOrigin)
  } catch {
    errors.push('AUTH_ALLOWED_ORIGINS 必须是逗号分隔的规范 HTTPS 域名 Origin')
  }
  let origin
  try {
    origin = parseOrigin(values.MINIO_PUBLIC_ENDPOINT)
    if (Number(origin.port || '443') !== port) throw new Error()
  } catch {
    errors.push('MINIO_PUBLIC_ENDPOINT 必须是与下载网关端口匹配的单个 HTTPS 域名 Origin')
    origin = undefined
  }
  if (values.FEISHU_OAUTH_REDIRECT_URI) {
    try {
      const callback = new URL(values.FEISHU_OAUTH_REDIRECT_URI)
      parseOrigin(callback.origin)
      if (callback.username || callback.password || callback.search || callback.hash ||
          callback.pathname !== '/api/v1/connector/oauth/callback') throw new Error()
    } catch {
      errors.push('FEISHU_OAUTH_REDIRECT_URI 必须是安全的 HTTPS 授权回调地址')
    }
  }
  for (const name of ['TLS_CERT_FILE', 'TLS_KEY_FILE']) {
    bad(!isAbsolute(values[name] || ''), `${name} 必须是证书文件的绝对路径`)
  }
  if (checkCertificate && origin && isAbsolute(values.TLS_CERT_FILE || '') && isAbsolute(values.TLS_KEY_FILE || '')) {
    try {
      const certificate = new X509Certificate(readFileSync(values.TLS_CERT_FILE))
      const privateKey = createPrivateKey(readFileSync(values.TLS_KEY_FILE))
      const certPublic = certificate.publicKey.export({ type: 'spki', format: 'der' })
      const keyPublic = createPublicKey(privateKey).export({ type: 'spki', format: 'der' })
      if (!certPublic.equals(keyPublic) || !/(?:^|,\s*)DNS:/.test(certificate.subjectAltName || '') ||
          !certificate.checkHost(origin.hostname) ||
          Date.now() < Date.parse(certificate.validFrom) || Date.now() >= Date.parse(certificate.validTo)) {
        throw new Error()
      }
    } catch {
      errors.push('TLS 证书/私钥不可读取、不匹配、过期或域名不匹配')
    }
  }
  return errors
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  try {
    if (process.argv.length !== 3) throw new Error('用法: node prod-config-check.mjs <环境文件>')
    const fileValues = parseEnv(readFileSync(process.argv[2], 'utf8'))
    // Compose 对所有变量都优先使用当前进程环境，包括文件中未出现的键及空值。
    const values = { ...fileValues, ...process.env }
    const errors = validate(values)
    if (errors.length) throw new Error(errors.join('\n'))
    console.log('生产配置格式及 TLS 证书检查通过；不代表真实网络或生产验收通过')
  } catch (error) {
    console.error(`生产配置检查失败：${error.message}`)
    process.exitCode = 1
  }
}
