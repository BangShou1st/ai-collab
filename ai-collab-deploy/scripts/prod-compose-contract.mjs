// 从 docker compose config --format json 的标准输入检查展开后的网络与安全契约。
import { assertAppDbRole } from './db-role-contract.mjs'
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

// ===== RocketMQ 常驻契约（设计 2026-11-25）：三容器必须存在，不挂 profile =====
const mqNames = ['rocketmq-namesrv', 'rocketmq-broker', 'rocketmq-topic-init']
for (const name of mqNames) {
  const service = config.services[name]
  assert.ok(service, `${name} 必须常驻于拓扑中`)
  assert.ok(!service.profiles?.length, `${name} 不得挂 profile`)
  assert.ok(!service.ports?.length, `${name} 不得发布宿主机端口`)
  assert.match(service.image, /@sha256:[a-f0-9]{64}$/, 'rocketmq 镜像必须 digest 固定')
}
const brokerConf = readFileSync(fileURLToPath(new URL('../rocketmq/broker.conf', import.meta.url)), 'utf8')
assert.match(brokerConf, /autoCreateTopicEnable\s*=\s*false/, 'broker 必须关闭自动创建 topic')
assert.match(brokerConf, /brokerIP1\s*=\s*rocketmq-broker/, 'brokerIP1 必须使用 compose 服务名')
assert.match(String(config.services['rocketmq-broker'].environment.JAVA_OPT_EXT), /-Xms256m -Xmx512m/, 'broker 堆内存 256-512MB')
assert.equal(app.environment.MQ_ENABLED, 'true', 'MQ_ENABLED 默认必须为 true（false 仅限运维紧急关闭）')
assert.equal(app.environment.ROCKETMQ_NAMESRV_ADDR, 'rocketmq-namesrv:9876')
const topicInitCmd = (config.services['rocketmq-topic-init'].command || []).join(String.fromCharCode(10))
assert.ok(topicInitCmd.includes('$$ROCKETMQ_DOCUMENT_TOPIC'), 'topic-init 必须预建文档注册 topic')
assert.ok(topicInitCmd.includes('%DLQ%$$ROCKETMQ_CONSUMER_GROUP'), 'topic-init 必须预建文档死信 topic')
assert.ok(!/AGENT_RUN|PLANNING/.test(topicInitCmd + JSON.stringify(config.services['rocketmq-topic-init'].environment)), 'topic-init 不得再声明 agent/planning topic')
for (const [name, condition] of [['rocketmq-broker', 'service_healthy'],
  ['rocketmq-topic-init', 'service_completed_successfully'], ['db-init', 'service_completed_successfully']]) {
  assert.equal(app.depends_on[name]?.condition, condition, `app 必须依赖 ${name} ${condition}`)
}

// ===== 数据库角色初始化：幂等一次性 db-init，ai_worker 密码只经环境变量传入 =====
const dbInit = config.services['db-init']
assert.ok(dbInit, 'db-init 一次性服务必须存在')
assert.equal(dbInit.depends_on.postgres.condition, 'service_healthy')
assert.ok(dbInit.environment.AI_WORKER_DB_PASSWORD, 'db-init 必须接收 AI_WORKER_DB_PASSWORD')
assert.ok(!(dbInit.command || []).join(' ').includes('AI_WORKER_DB_PASSWORD'), 'ai_worker 密码不得出现在命令行参数')
assert.ok((dbInit.volumes || []).some(v => String(v.target) === '/db-init/db-init.sql'), 'db-init 必须挂载 db-init.sql')

// ===== 内部 AI 令牌：只有 Ed25519，已删除的 mode/shadow/static 变量不得回流 =====
assert.ok(app.environment.INTERNAL_AI_ED25519_PRIVATE_KEY, 'INTERNAL_AI_ED25519_PRIVATE_KEY 必须注入')
const removedKey = /^(RERANK_(MODE|SERVICE_TOKEN|MODEL_USER_ID|SHADOW_.*)|DECIDE_.*|PLANNING_DRAFT_.*|INTERNAL_AI_TOKEN_MODE|PLANNING_MQ_ENABLED|AGENT_MQ_ENABLED|AI_WORKER_TOKEN_MODE|AI_WORKER_SERVICE_TOKEN|ROCKETMQ_(AGENT_RUN|PLANNING)_.*|AGENT_MQ_FALLBACK_POLL_MS|AI_WORKER_ALLOW_INSECURE_INTERNAL|AI_WORKER_MODEL_ALIAS|AI_WORKER_DECIDE_.*|AI_WORKER_PLANNING_.*)$/
for (const [name, service] of Object.entries(config.services)) {
  for (const key of Object.keys(service.environment || {})) {
    assert.ok(!removedKey.test(key), `${name} 仍引用已删除变量 ${key}`)
  }
}
// ===== C0.1：应用数据库角色非超级用户，超级用户凭据不进应用实例 =====
assertAppDbRole(config.services, ['app'])

console.log('production compose network and configuration contract: OK')
