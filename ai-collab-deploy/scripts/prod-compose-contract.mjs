// 从 docker compose config --format json 的标准输入检查展开后的网络与安全契约。
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
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

// ===== 可观测性 O1 契约：独立管理端口边界 =====
// 8081 绝不映射宿主机端口（app 与所有服务均不得发布 target=8081 的端口）。
for (const [name, service] of Object.entries(config.services)) {
  for (const port of service.ports || []) {
    assert.notEqual(Number(port.target), 8081, `${name} 不得发布管理端口 8081`)
  }
}
// 健康检查指向管理端口 8081（容器 HEALTHCHECK 与 smoke 脚本同源）。
assert.match(String(app.healthcheck?.test ?? ''), /8081\/actuator\/health/, 'app healthcheck 必须指向管理端口 8081')
// 生产 exposure 含 prometheus（指标端点就绪，面板容器按设计只进 local/staging）。
assert.equal(app.environment.MANAGEMENT_ENDPOINTS_EXPOSURE, 'health,info,prometheus')
// nginx 配置不含 8081：管理端口不经代理对外暴露（对外 404 断言由 p0-https-test.mjs 保障）。
const nginxConf = readFileSync(fileURLToPath(new URL('../nginx/production.conf', import.meta.url)), 'utf8')
assert.ok(!nginxConf.includes('8081'), 'nginx 生产配置不得引用管理端口 8081')
console.log('production compose network and configuration contract: OK')
