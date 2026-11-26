// 从 docker compose config --format json 的标准输入检查本地一键部署拓扑契约。
// 用法：docker compose --env-file <local.env> -f ai-collab-deploy/compose.local.yml config --format json | node local-compose-contract.mjs
// 守卫目标：入口只绑 127.0.0.1、数据面零端口发布、HTTP Cookie 例外成对出现且不外溢、网络隔离。
import { assertAppDbRole } from './db-role-contract.mjs'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import assert from 'node:assert/strict'
const config = JSON.parse(readFileSync(0, 'utf8'))
const { postgres, redis, minio, app, web, mailpit, 'rocketmq-namesrv': namesrv, 'rocketmq-broker': broker, 'rocketmq-topic-init': topicInit } = config.services

// 数据面与后端不发布任何宿主机端口。
for (const [name, service] of Object.entries({ postgres, redis, minio, app, namesrv, broker, topicInit })) {
  assert.ok(!service.ports?.length, `${name} 不应发布端口`)
}
// 入口仅一个端口，且必须绑定本机回环地址。
assert.equal(web.ports.length, 1, 'web 只应发布一个端口')
const binding = web.ports[0]
assert.equal(binding.host_ip, '127.0.0.1', 'web 入口必须只绑定 127.0.0.1')
assert.equal(Number(binding.target), 80)

// 本地 HTTP Cookie 例外必须成对出现（后端启动守卫也校验），且仅存在于本地拓扑。
assert.equal(app.environment.REFRESH_COOKIE_SECURE, 'false')
assert.equal(app.environment.REFRESH_COOKIE_ALLOW_INSECURE, 'true')
// 下载走同源代理路径，指向回环 HTTP 入口。
assert.match(app.environment.MINIO_PUBLIC_ENDPOINT, /^http:\/\/localhost:\d+$/)
assert.equal(app.environment.MINIO_MANAGE_BUCKET, 'true')
assert.match(app.environment.AUTH_ALLOWED_ORIGINS, /^http:\/\/localhost:\d+$/)

// 网络隔离：数据面 internal；网段与生产/Staging（172.30.173.0/24）不重叠。
assert.equal(config.networks.data.internal, true)
assert.equal(config.networks.edge.ipam.config[0].subnet, '172.30.174.0/24')
assert.ok(!Object.values(app.networks).some((n) => (n.ipv4_address || '') === '172.30.173.2'), '本地拓扑不得占用生产受信代理地址')
// 存储鉴权受信代理锚点：web 固定 172.30.174.2，应用显式信任该地址。
assert.equal(app.environment.STORAGE_MINIO_TRUSTEDPROXYADDRESS, '172.30.174.2')
assert.equal(web.networks.edge.ipv4_address, '172.30.174.2')

// 基础镜像 digest 固定；数据面与入口有持久化/健康检查。
for (const service of [postgres, redis, minio]) {
  assert.ok(service.volumes.length, `${service} 缺少数据卷`)
  assert.match(service.image, /@sha256:[a-f0-9]{64}$/)
}
assert.equal(app.depends_on.minio.condition, 'service_healthy')
assert.ok(web.healthcheck, 'web 缺少健康检查')

// mailpit 仅在可选 profile 中，且收件箱 UI 也只绑回环。
if (mailpit) {
  assert.ok((mailpit.profiles || []).includes('maildev'))
  for (const p of mailpit.ports) assert.equal(p.host_ip, '127.0.0.1', 'mailpit 端口必须只绑定 127.0.0.1')
}

/// ===== RocketMQ 常驻契约（设计 2026-11-25）：三容器必须存在，不挂 profile =====
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

console.log('local compose network and configuration contract: OK')
