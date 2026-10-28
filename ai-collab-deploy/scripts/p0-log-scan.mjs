// 仅检查隔离项目的合成机密，不输出匹配值或日志行。
import { execFileSync, spawnSync } from 'node:child_process'
import assert from 'node:assert/strict'
const project = process.argv[2] || ''
assert.match(project, /^p0iso[0-9]+$/)
const names = ['app', 'postgres', 'redis', 'minio', 'proxy'].map(role => `${project}-${role}-1`)
const secrets = new Set(['p0-synthetic-object', 'p0-encoded-object', 'p0-synthetic-upload'])
let logs = ''
for (const name of names) {
  const inspected = JSON.parse(execFileSync('docker', ['inspect', name], { encoding: 'utf8' }))[0]
  assert.equal(inspected.Config.Labels['com.docker.compose.project'], project)
  for (const line of inspected.Config.Env || []) {
    const pos = line.indexOf('='); const key = line.slice(0, pos); const value = line.slice(pos + 1)
    if (/^(POSTGRES_PASSWORD|SPRING_DATASOURCE_PASSWORD|REDIS_PASSWORD|SPRING_DATA_REDIS_PASSWORD|MINIO_ROOT_PASSWORD|MINIO_SECRET_KEY|JWT_SECRET|MODEL_CONFIG_MASTER_KEY)$/.test(key) && value) secrets.add(value)
  }
  const result = spawnSync('docker', ['logs', name], { encoding: 'utf8', maxBuffer: 20 * 1024 * 1024 })
  if (result.status !== 0) throw new Error(`cannot read logs for ${name}: ${result.status}`)
  logs += result.stdout + result.stderr
}
const leaks = [...secrets].filter(value => logs.includes(value)).length
assert.equal(leaks, 0, '日志包含合成机密或文档标记')
assert.doesNotMatch(logs, /X-Amz-Signature=|eyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+/)
console.log('isolated public logs: synthetic secret, signed URL, JWT and document marker scan passed')
