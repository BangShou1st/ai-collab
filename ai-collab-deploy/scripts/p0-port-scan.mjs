import assert from 'node:assert/strict'
import { execFileSync } from 'node:child_process'
const project = process.argv[2] || ''
assert.match(project, /^p0iso[0-9]+$/)
for (const role of ['app', 'postgres', 'redis', 'minio', 'proxy']) {
  const name = `${project}-${role}-1`
  const inspected = JSON.parse(execFileSync('docker', ['inspect', name], { encoding: 'utf8' }))[0]
  assert.equal(inspected.Config.Labels['com.docker.compose.project'], project)
  const published = Object.values(inspected.NetworkSettings.Ports || {}).flatMap(v => v || [])
  if (role === 'proxy') {
    assert.equal(published.length, 1)
    assert.equal(published[0].HostIp, '127.0.0.1')
  } else assert.equal(published.length, 0, `${role} unexpectedly published a host port`)
}
console.log('isolated Docker port bindings: only proxy on IPv4 loopback')
