// 从 docker compose config --format json 的标准输入检查展开后的网络与安全契约。
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import assert from 'node:assert/strict'
const config = JSON.parse(readFileSync(0, 'utf8'))
const { postgres, redis, minio, app, proxy, 'rocketmq-namesrv': namesrv, 'rocketmq-broker': broker, 'rocketmq-topic-init': topicInit } = config.services
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

// ===== MQ 迁移 M1 契约：RocketMQ 数据面边界与灰度开关 =====
// namesrv/broker/topic-init 全部为数据面容器：不发布宿主机端口、只进 internal 网络。
for (const [name, service] of Object.entries({ namesrv, broker, topicInit })) {
  assert.ok(!service.ports?.length, `${name} 不应发布宿主机端口`)
  assert.ok(service.networks && 'data' in service.networks, `${name} 必须只挂 data 内网`)
}
// 镜像按 digest 固定（与 postgres/redis/minio 同一安全基线）。
for (const service of [namesrv, broker, topicInit]) {
  assert.match(service.image, /@sha256:[a-f0-9]{64}$/, 'rocketmq 镜像必须 digest 固定')
}
// broker.conf 显式声明：自动创建 topic 关闭（topic 由 rocketmq-topic-init 显式声明）。
const brokerConf = readFileSync(fileURLToPath(new URL('../rocketmq/broker.conf', import.meta.url)), 'utf8')
assert.match(brokerConf, /autoCreateTopicEnable\s*=\s*false/, 'broker 必须关闭自动创建 topic')
assert.match(brokerConf, /brokerIP1\s*=\s*rocketmq-broker/, 'brokerIP1 必须使用 compose 服务名')
// 生产灰度开关语义：MQ_ENABLED 默认 false（文档链路完全回落进程内路径，仅限单实例）。
assert.equal(app.environment.MQ_ENABLED, 'false')
// 客户端经 compose 内网服务名连 NameServer；app 不发布任何 MQ 端口（上面的通用断言已覆盖）。
assert.equal(app.environment.ROCKETMQ_NAMESRV_ADDR, 'rocketmq-namesrv:9876')
// ===== MQ 迁移 M2 契约：agent run 入队通知与规划 dispatch 的业务 topic / 消费组 =====
// topic-init 必须声明两条新链路的 env；app 必须透传同名变量（总闸随 MQ_ENABLED 灰度）。
for (const [key, defaultValue] of [
  ['ROCKETMQ_AGENT_RUN_TOPIC', 'agent-run-queued'],
  ['ROCKETMQ_AGENT_RUN_CONSUMER_GROUP', 'agent-run-queued-consumer'],
  ['ROCKETMQ_PLANNING_TOPIC', 'plan-generation'],
  ['ROCKETMQ_PLANNING_CONSUMER_GROUP', 'plan-generation-consumer'],
]) {
  const declared = topicInit.environment[key]
  assert.ok(declared, `rocketmq-topic-init 必须声明 ${key}`)
  assert.ok(String(declared).includes(defaultValue), `${key} 默认值契约`)
  assert.ok(app.environment[key], `app 必须透传 ${key}`)
}
// topic-init 命令必须为两条新链路预建业务 topic、死信 topic（%DLQ%）与消费组。
const topicInitCmd = JSON.stringify(topicInit.command)
assert.ok(topicInitCmd.includes('ROCKETMQ_AGENT_RUN_TOPIC'), 'topic-init 必须预建 agent run topic')
assert.ok(topicInitCmd.includes('%DLQ%$$ROCKETMQ_AGENT_RUN_CONSUMER_GROUP'), 'topic-init 必须预建 agent run 死信 topic')
assert.ok(topicInitCmd.includes('ROCKETMQ_PLANNING_TOPIC'), 'topic-init 必须预建规划 dispatch topic')
assert.ok(topicInitCmd.includes('%DLQ%$$ROCKETMQ_PLANNING_CONSUMER_GROUP'), 'topic-init 必须预建规划 dispatch 死信 topic')
console.log('production compose network and configuration contract: OK')
