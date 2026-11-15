// 从 docker compose config --format json 的标准输入检查 staging 安全边界。
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const config = JSON.parse(readFileSync(0, 'utf8'))
const services = config.services

for (const name of ['postgres', 'redis', 'minio', 'app']) {
  assert.ok(!services[name].ports?.length, `${name} 不得发布宿主端口`)
}
assert.equal(services.proxy.ports.length, 1)
assert.equal(Number(services.proxy.ports[0].target), 80)
assert.equal(Number(services.proxy.ports[0].published), 8443)
assert.equal(config.networks.data.internal, true)

assert.equal(services.app.environment.SPRING_PROFILES_ACTIVE, 'production')
assert.equal(services.app.environment.REFRESH_COOKIE_SECURE, 'true')
assert.equal(services.app.environment.REFRESH_COOKIE_ALLOW_INSECURE, 'false')
assert.match(services.app.environment.MQ_ENABLED, /^(true|false)$/)
assert.equal(services.app.environment.AGENT_MCP_GATEWAY_ENABLED, undefined)
// staging 已接入 ai-worker（Memory Job / QueryPlan / Prompt Registry / Rerank），
// 因此内部 AI 令牌模式是合法配置；边界改为只接受三种冻结模式，且网关仍必须关闭。
assert.match(services.app.environment.INTERNAL_AI_TOKEN_MODE, /^(static|ed25519|dual)$/)

console.log('staging compose network, data-plane, cookie, internal-AI and optional-switch contract: OK')
