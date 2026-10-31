import test from 'node:test'
import assert from 'node:assert/strict'
import { spawnSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'

const deployment = fileURLToPath(new URL('..', import.meta.url))
const env = {
  PATH: process.env.PATH,
  SystemRoot: process.env.SystemRoot,
  HOME: process.env.HOME,
  USERPROFILE: process.env.USERPROFILE,
  APPDATA: process.env.APPDATA,
  LOCALAPPDATA: process.env.LOCALAPPDATA,
  ProgramFiles: process.env.ProgramFiles,
  AUTH_ALLOWED_ORIGINS: 'https://app.example.org,https://admin.example.org',
  MINIO_PUBLIC_ENDPOINT: 'https://storage.example.org',
}

for (const file of ['compose.staging.yml', 'compose.prod.yml']) {
  test(`${file} passes an independent download origin`, () => {
    const result = spawnSync('docker', ['compose', '--env-file', 'prod.env.example', '-f', file,
      'config', '--format', 'json'], { cwd: deployment, env, encoding: 'utf8' })
    assert.equal(result.status, 0, 'compose config failed')
    const app = JSON.parse(result.stdout).services.app.environment
    assert.equal(app.MINIO_ENDPOINT, 'http://minio:9000')
    assert.equal(app.MINIO_PUBLIC_ENDPOINT, 'https://storage.example.org')
    assert.equal(app.MINIO_REQUIRE_PUBLIC_ENDPOINT, 'true')
    assert.equal(app.AUTH_ALLOWED_ORIGINS, env.AUTH_ALLOWED_ORIGINS)
  })
}
