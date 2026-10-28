// 从 docker compose config --format json 的标准输入检查展开后的网络与安全契约。
import { readFileSync } from 'node:fs'
import assert from 'node:assert/strict'
const config = JSON.parse(readFileSync(0, 'utf8'))
const { postgres, redis, minio, app, proxy } = config.services
for (const [name, service] of Object.entries({ postgres, redis, minio, app })) {
  assert.ok(!service.ports?.length, `${name} 不应发布端口`)
}
assert.equal(proxy.ports.length, 1)
assert.equal(Number(proxy.ports[0].target), 443)
assert.equal(proxy.networks.edge.ipv4_address, '172.30.173.2')
assert.equal(config.networks.edge.ipam.config[0].ip_range, '172.30.173.128/25')
assert.equal(config.networks.data.internal, true)
assert.equal(app.environment.SPRING_PROFILES_ACTIVE, 'production')
assert.equal(app.environment.AUTH_PUBLIC_REGISTRATION_ENABLED, process.env.P0_EXPECT_REGISTRATION || 'true')
assert.equal(app.environment.REFRESH_COOKIE_SECURE, 'true')
assert.equal(app.environment.REFRESH_COOKIE_ALLOW_INSECURE, 'false')
assert.equal(app.environment.MINIO_MANAGE_BUCKET, 'false')
assert.equal(app.depends_on.minio.condition, 'service_healthy')
for (const service of [postgres, redis, minio]) assert.ok(service.volumes.length)
for (const service of [postgres, redis, minio]) assert.match(service.image, /@sha256:[a-f0-9]{64}$/)
console.log('production compose network and configuration contract: OK')
