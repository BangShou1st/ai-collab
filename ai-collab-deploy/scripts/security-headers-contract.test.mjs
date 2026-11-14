import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import test from 'node:test'
import { fileURLToPath } from 'node:url'

const root = resolve(fileURLToPath(new URL('.', import.meta.url)), '../..')

function read(relativePath) {
  return readFileSync(resolve(root, relativePath), 'utf8')
}

function assertHstsResponseBoundary(config, label) {
  assert.match(config, /proxy_hide_header\s+Strict-Transport-Security;/,
    `${label} 必须隐藏上游 HSTS，避免重复头`)
  assert.match(config,
    /add_header\s+Strict-Transport-Security\s+"max-age=31536000;\s*includeSubDomains"\s+always;/,
    `${label} 必须为所有响应发出 HSTS`)
}

function assertInternalRoutesAreBlocked(config, label) {
  assert.match(config, /location\s+\^~\s+\/internal\/\s*\{\s*return\s+404;\s*\}/,
    `${label} 必须显式阻断内部 AI/MCP 路径，不能落入 SPA fallback`)
}

test('生产与 staging 边缘入口发出单一 HSTS 头并阻断内部路径', () => {
  for (const filename of ['production.conf', 'staging.conf']) {
    const config = read(`ai-collab-deploy/nginx/${filename}`)
    assertHstsResponseBoundary(config, filename)
    assertInternalRoutesAreBlocked(config, filename)
  }
})

test('Cloudflare Pages 静态入口通过 _headers 建立 HSTS 策略', () => {
  const headers = read('ai-collab-frontend/public/_headers')
  assert.match(headers, /^\/\*$/m)
  assert.match(headers,
    /^\s*Strict-Transport-Security:\s*max-age=31536000;\s*includeSubDomains$/m)
})
