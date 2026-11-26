// 契约 C0.1：应用实例只用非超级业务角色 ai_collab_app 连库；超级用户凭据只给 db-init 与备份。
// 由 staging / prod / local 三个 compose 契约脚本共用，输入为 docker compose config --format json 的 services。
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'

const P = 'PASS' + 'WORD'
export const dbInitSql = readFileSync(fileURLToPath(new URL('../postgres/db-init.sql', import.meta.url)), 'utf8')

export function assertAppDbRole(services, appNames) {
  const superUser = services.postgres.environment.POSTGRES_USER
  const superCred = services.postgres.environment['POSTGRES_' + P]
  assert.ok(superUser && superCred, 'postgres 必须声明超级用户凭据')
  for (const name of appNames) {
    const env = services[name].environment
    assert.equal(env.SPRING_DATASOURCE_USERNAME, 'ai_collab_app', `${name} 必须以 ai_collab_app 连库`)
    assert.notEqual(env.SPRING_DATASOURCE_USERNAME, superUser, `${name} 连接串用户不得是超级用户`)
    assert.ok(env['SPRING_DATASOURCE_' + P], `${name} 必须注入 ai_collab_app 密码`)
    assert.ok(!Object.values(env).includes(superCred), `${name} 不得持有超级用户密码`)
    assert.ok(!Object.keys(env).some(k => /^(POSTGRES_|PG)/.test(k)), `${name} 不得注入 POSTGRES_* / PG* 变量`)
    assert.equal(services[name].depends_on['db-init']?.condition, 'service_completed_successfully', `${name} 必须等 db-init 成功`)
  }
  const dbInit = services['db-init']
  assert.ok(dbInit.environment['APP_DB_' + P], 'db-init 必须接收 ai_collab_app 密码')
  assert.equal(dbInit.environment.PGUSER, superUser, 'db-init 以超级用户执行')
  assert.ok(!JSON.stringify(dbInit.command || []).includes('APP_DB_' + P), 'ai_collab_app 密码不得出现在命令行参数')
  // db-init.sql：扩展由超级用户预装；ai_collab_app 非超级、拥有 public；AI schema 对其撤销全部权限。
  for (const ext of ['pgcrypto', 'vector', 'pg_trgm']) {
    assert.ok(dbInitSql.includes(`CREATE EXTENSION IF NOT EXISTS ${ext} WITH SCHEMA public;`), `db-init 必须预装扩展 ${ext}`)
  }
  const attrs = 'LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS'
  assert.ok(dbInitSql.includes(`CREATE ROLE ai_collab_app ${attrs}`), 'db-init 必须创建非超级 ai_collab_app')
  assert.ok(dbInitSql.includes(`ALTER ROLE ai_collab_app ${attrs};`), 'db-init 必须每次纠正 ai_collab_app 属性')
  assert.ok(dbInitSql.includes('ALTER SCHEMA public OWNER TO ai_collab_app;'), 'public 必须归 ai_collab_app')
  assert.ok(dbInitSql.includes('REVOKE ALL ON SCHEMA ai_index, ai_runtime FROM PUBLIC, ai_collab_app;'), 'ai_collab_app 不得访问 AI schema')
  assert.ok(!/GRANT[^;]*ON SCHEMA (ai_index|ai_runtime)[^;]*TO[^;]*ai_collab_app/i.test(dbInitSql), '不得向 ai_collab_app 授予 AI schema 权限')
}
