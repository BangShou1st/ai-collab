// 从 docker compose config --format json 的标准输入检查 staging 安全边界与双实例拓扑。
import { assertAppDbRole } from './db-role-contract.mjs'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'

const config = JSON.parse(readFileSync(0, 'utf8'))
const services = config.services
const apps = ['app-1', 'app-2']

// 双实例：单实例服务名 app 已拆除；两实例同镜像、同 env（仅 APP_INSTANCE_ID 不同）、各自健康检查。
assert.equal(services.app, undefined, '单实例 app 服务必须拆为 app-1/app-2')
for (const name of ['postgres', 'redis', 'minio', ...apps, 'db-init',
  'rocketmq-namesrv', 'rocketmq-broker', 'rocketmq-topic-init', 'ai-worker']) {
  assert.ok(services[name], `${name} 必须存在`)
  assert.ok(!services[name].ports?.length, `${name} 不得发布宿主端口`)
}
assert.equal(services.proxy.ports.length, 1)
assert.equal(Number(services.proxy.ports[0].target), 80)
assert.equal(Number(services.proxy.ports[0].published), 8443)
assert.equal(config.networks.data.internal, true)

const [a1, a2] = apps.map(name => services[name])
assert.equal(a1.image, a2.image, '两实例必须使用同一镜像')
const stripInstance = env => Object.fromEntries(Object.entries(env).filter(([k]) => k !== 'APP_INSTANCE_ID'))
assert.deepEqual(stripInstance(a1.environment), stripInstance(a2.environment), '两实例 env 必须一致')
assert.equal(a1.environment.APP_INSTANCE_ID, 'app-1')
assert.equal(a2.environment.APP_INSTANCE_ID, 'app-2')
for (const [index, app] of [a1, a2].entries()) {
  const name = apps[index]
  assert.equal(app.container_name, undefined, `${name} 不得固定 container_name`)
  assert.match(String(app.healthcheck?.test ?? ''), /8081\/actuator\/health/, `${name} 健康检查必须走 8081`)
  assert.deepEqual(app.networks.edge.aliases, [`${name}-edge`], `${name} 必须有 edge 别名供 nginx upstream 使用`)
  assert.ok(app.networks.data.aliases.includes('app-internal'), `${name} 必须加入 data 别名 app-internal`)
  for (const [dep, condition] of [['postgres', 'service_healthy'], ['db-init', 'service_completed_successfully'],
    ['rocketmq-broker', 'service_healthy'], ['rocketmq-topic-init', 'service_completed_successfully']]) {
    assert.equal(app.depends_on[dep]?.condition, condition, `${name} 必须依赖 ${dep} ${condition}`)
  }
  const env = app.environment
  assert.equal(env.SPRING_PROFILES_ACTIVE, 'production')
  assert.equal(env.REFRESH_COOKIE_SECURE, 'true')
  assert.equal(env.REFRESH_COOKIE_ALLOW_INSECURE, 'false')
  assert.equal(env.MQ_ENABLED, 'true', 'MQ_ENABLED 默认 true（false 仅限运维紧急关闭）')
  assert.equal(env.AGENT_MCP_GATEWAY_ENABLED, undefined)
  assert.ok(env.INTERNAL_AI_ED25519_PRIVATE_KEY, 'app 必须注入 Ed25519 私钥')
}
assert.ok(!services.proxy.depends_on.app, 'proxy 不得再依赖单实例 app')
for (const name of apps) assert.ok(services.proxy.depends_on[name], `proxy 必须依赖 ${name}`)

// RocketMQ 常驻（无 profile），topic-init 只声明文档注册 topic。
for (const name of ['rocketmq-namesrv', 'rocketmq-broker', 'rocketmq-topic-init']) {
  assert.ok(!services[name].profiles?.length, `${name} 不得挂 profile`)
}
assert.match(String(services['rocketmq-broker'].environment.JAVA_OPT_EXT), /-Xms256m -Xmx512m/)
assert.ok(!/AGENT_RUN|PLANNING/.test(JSON.stringify(services['rocketmq-topic-init'])), 'topic-init 不得声明 agent/planning topic')

// ai-worker：只持公钥、内存上限 1g、P0 不接数据库凭据。
const worker = services['ai-worker']
assert.ok(worker.environment.AI_WORKER_ED25519_PUBLIC_KEYS, 'worker 必须注入 Ed25519 公钥')
assert.equal(worker.environment.AI_WORKER_JAVA_GATEWAY_URL, 'http://app-internal:8080')
assert.equal(Number(worker.mem_limit), 1024 ** 3, 'ai-worker 内存上限必须为 1g')
assert.ok(!Object.keys(worker.environment).some(k => /PRIVATE_KEY|DB_|DATASOURCE|POSTGRES/.test(k)),
  'ai-worker 不得持有私钥或数据库凭据')

// 已删除的 mode/shadow/static 变量不得回流到任何服务。
const removedKey = /^(RERANK_(MODE|SERVICE_TOKEN|MODEL_USER_ID|SHADOW_.*)|DECIDE_.*|PLANNING_DRAFT_.*|INTERNAL_AI_TOKEN_MODE|PLANNING_MQ_ENABLED|AGENT_MQ_ENABLED|AI_WORKER_TOKEN_MODE|AI_WORKER_SERVICE_TOKEN|ROCKETMQ_(AGENT_RUN|PLANNING)_.*|AGENT_MQ_FALLBACK_POLL_MS|AI_WORKER_ALLOW_INSECURE_INTERNAL|AI_WORKER_MODEL_ALIAS|AI_WORKER_DECIDE_.*|AI_WORKER_PLANNING_.*)$/
for (const [name, service] of Object.entries(services)) {
  for (const key of Object.keys(service.environment || {})) {
    assert.ok(!removedKey.test(key), `${name} 仍引用已删除变量 ${key}`)
  }
}

// db-init：密码只经环境变量，不进命令行。
const dbInit = services['db-init']
assert.ok(dbInit.environment.AI_WORKER_DB_PASSWORD)
assert.ok(!(dbInit.command || []).join(' ').includes('AI_WORKER_DB_PASSWORD'))

// Prometheus 抓取两个实例；nginx 显式 upstream 含两实例、SSE 不缓冲、无会话粘性。
const prom = readFileSync(fileURLToPath(new URL('../observability/prometheus.staging.yml', import.meta.url)), 'utf8')
for (const name of apps) assert.ok(prom.includes(`${name}:8081`), `prometheus 必须抓取 ${name}`)
const nginx = readFileSync(fileURLToPath(new URL('../nginx/staging.conf', import.meta.url)), 'utf8')
assert.match(nginx, /upstream app_backend \{[^}]*server app-1-edge:8080 resolve[^}]*server app-2-edge:8080 resolve/s)
assert.ok(!/\bip_hash\b|\bsticky\b|\bhash\s+\$/.test(nginx), '不得配置会话粘性')
assert.ok(!nginx.includes('app-edge'), 'nginx 不得再引用单实例别名 app-edge')
const sse = /location \/api\/v1\/ \{([^}]*)\}/.exec(nginx)?.[1] ?? ''
for (const directive of ['proxy_buffering off', 'proxy_cache off', 'proxy_read_timeout 3600s']) {
  assert.ok(sse.includes(directive), `/api/v1/ 必须保留 ${directive}`)
}

// ===== C0.1：应用数据库角色非超级用户，超级用户凭据不进应用实例 =====
assertAppDbRole(config.services, apps)

console.log('staging compose dual-instance, network, data-plane, MQ, token and db-init contract: OK')
