// 从 docker compose config --format json 的标准输入检查本地一键部署拓扑契约。
// 用法：docker compose --env-file <local.env> -f ai-collab-deploy/compose.local.yml config --format json | node local-compose-contract.mjs
// 守卫目标：入口只绑 127.0.0.1、数据面零端口发布、HTTP Cookie 例外成对出现且不外溢、网络隔离。
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

// ===== MQ 迁移 M1 契约：本地拓扑内建 RocketMQ，数据面零端口发布 =====
for (const service of [namesrv, broker, topicInit]) {
  assert.ok(service, '本地拓扑必须包含 RocketMQ 三容器（namesrv/broker/topic-init）')
  assert.match(service.image, /@sha256:[a-f0-9]{64}$/, 'rocketmq 镜像必须 digest 固定')
}
const brokerConf = readFileSync(fileURLToPath(new URL('../rocketmq/broker.conf', import.meta.url)), 'utf8')
assert.match(brokerConf, /autoCreateTopicEnable\s*=\s*false/, 'broker 必须关闭自动创建 topic')
// 总闸默认开启（本地一键部署随拓扑起 MQ）；允许显式回落，但值必须非空。
assert.match(app.environment.MQ_ENABLED, /^(true|false)$/, 'MQ_ENABLED 必须显式渲染为 true/false')
assert.equal(app.environment.ROCKETMQ_NAMESRV_ADDR, 'rocketmq-namesrv:9876')
console.log('local compose network and configuration contract: OK')
