-- 一次性幂等初始化（compose 的 db-init 服务每次 up 以超级用户执行；依赖 psql 的 \getenv / \gexec）。
-- 作用（契约 C0 / C0.1）：
--   * 扩展 pgcrypto / vector / pg_trgm 由超级用户预装在 public，Flyway 迁移里的
--     CREATE EXTENSION IF NOT EXISTS 因此只是空操作，应用角色无需建扩展权限；
--   * ai_collab_app：非超级业务角色，是 public schema 及其中业务对象的 owner，Flyway 以它执行迁移；
--   * ai_worker：只拥有 ai_index、ai_runtime，读写不到 public 业务表；
--   * ai_collab_app 与 PUBLIC 对 ai_index、ai_runtime 无任何权限。
-- 输入：环境变量 APP_DB_PASSWORD、AI_WORKER_DB_PASSWORD（经 \getenv 读取，不出现在命令行参数和输出中）。
-- 超级用户（POSTGRES_USER）只留给 db-init 与备份使用，应用实例不持有其凭据。

\set ON_ERROR_STOP on
\getenv app_password APP_DB_PASSWORD
\if :{?app_password}
\else
  \set app_password ''
\endif
\getenv ai_worker_password AI_WORKER_DB_PASSWORD
\if :{?ai_worker_password}
\else
  \set ai_worker_password ''
\endif

-- 0. 前置校验：缺失或过短的密码、非超级用户执行、超级用户与应用角色同名，均以 SQL 异常失败
--    （ON_ERROR_STOP 使 psql 非零退出，依赖 db-init 成功完成的 app 不会启动）。
SELECT 'DO $chk$ BEGIN RAISE EXCEPTION ''APP_DB_PASSWORD 缺失或少于 16 个字符''; END $chk$'
WHERE length(:'app_password') < 16 \gexec
SELECT 'DO $chk$ BEGIN RAISE EXCEPTION ''AI_WORKER_DB_PASSWORD 缺失或少于 16 个字符''; END $chk$'
WHERE length(:'ai_worker_password') < 16 \gexec
SELECT 'DO $chk$ BEGIN RAISE EXCEPTION ''db-init 必须以超级用户执行，且 POSTGRES_USER 不能是 ai_collab_app 或 ai_worker''; END $chk$'
WHERE NOT (SELECT rolsuper FROM pg_roles WHERE rolname = current_user)
   OR current_user IN ('ai_collab_app', 'ai_worker') \gexec

-- 1. 迁移依赖的扩展：超级用户预装在 public。
CREATE EXTENSION IF NOT EXISTS pgcrypto WITH SCHEMA public;
CREATE EXTENSION IF NOT EXISTS vector WITH SCHEMA public;
CREATE EXTENSION IF NOT EXISTS pg_trgm WITH SCHEMA public;

-- 2. 角色：不存在才创建；每次纠正属性并同步密码，便于按 env 轮换。
SELECT 'CREATE ROLE ai_collab_app LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS'
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ai_collab_app') \gexec
ALTER ROLE ai_collab_app LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
SELECT format('ALTER ROLE ai_collab_app PASSWORD %L', :'app_password') \gexec
\unset app_password

SELECT 'CREATE ROLE ai_worker LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS'
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ai_worker') \gexec
ALTER ROLE ai_worker LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
SELECT format('ALTER ROLE ai_worker PASSWORD %L', :'ai_worker_password') \gexec
\unset ai_worker_password

-- 3. 业务 schema 归 ai_collab_app。已有数据卷（此前由超级用户跑过 Flyway）上，
--    把 public 中非扩展成员的表、视图、序列、函数、类型逐个转给 ai_collab_app；
--    已归属的对象不再产生语句，因此每次执行都是幂等的。
ALTER SCHEMA public OWNER TO ai_collab_app;
SELECT format('GRANT CONNECT, TEMPORARY ON DATABASE %I TO ai_collab_app', current_database()) \gexec

SELECT format('ALTER %s %I.%I OWNER TO ai_collab_app',
              CASE c.relkind WHEN 'v' THEN 'VIEW' WHEN 'm' THEN 'MATERIALIZED VIEW'
                             WHEN 'S' THEN 'SEQUENCE' WHEN 'f' THEN 'FOREIGN TABLE' ELSE 'TABLE' END,
              n.nspname, c.relname)
FROM pg_class c
JOIN pg_namespace n ON n.oid = c.relnamespace
WHERE n.nspname = 'public'
  AND c.relkind IN ('r', 'p', 'v', 'm', 'S', 'f')
  AND c.relowner <> 'ai_collab_app'::regrole
  AND NOT EXISTS (SELECT 1 FROM pg_depend d
                  WHERE d.classid = 'pg_class'::regclass AND d.objid = c.oid AND d.deptype = 'e')
  -- 随列/标识列所属的序列跟随表一起转移，不能单独 ALTER。
  AND NOT (c.relkind = 'S' AND EXISTS (SELECT 1 FROM pg_depend d
                  WHERE d.classid = 'pg_class'::regclass AND d.objid = c.oid AND d.deptype IN ('a', 'i')))
ORDER BY c.relkind = 'S', c.oid \gexec

SELECT format('ALTER %s %s OWNER TO ai_collab_app',
              CASE p.prokind WHEN 'p' THEN 'PROCEDURE' WHEN 'a' THEN 'AGGREGATE' ELSE 'FUNCTION' END,
              p.oid::regprocedure)
FROM pg_proc p
JOIN pg_namespace n ON n.oid = p.pronamespace
WHERE n.nspname = 'public'
  AND p.proowner <> 'ai_collab_app'::regrole
  AND NOT EXISTS (SELECT 1 FROM pg_depend d
                  WHERE d.classid = 'pg_proc'::regclass AND d.objid = p.oid AND d.deptype = 'e') \gexec

SELECT format('ALTER %s %I.%I OWNER TO ai_collab_app',
              CASE t.typtype WHEN 'd' THEN 'DOMAIN' ELSE 'TYPE' END, n.nspname, t.typname)
FROM pg_type t
JOIN pg_namespace n ON n.oid = t.typnamespace
WHERE n.nspname = 'public'
  AND t.typowner <> 'ai_collab_app'::regrole
  AND (t.typtype IN ('e', 'd', 'r')
       OR (t.typtype = 'c' AND (SELECT relkind FROM pg_class WHERE oid = t.typrelid) = 'c'))
  AND NOT EXISTS (SELECT 1 FROM pg_depend d
                  WHERE d.classid = 'pg_type'::regclass AND d.objid = t.oid AND d.deptype = 'e') \gexec

-- 4. AI schema：归 ai_worker 所有（已存在时也纠正属主）。
CREATE SCHEMA IF NOT EXISTS ai_index AUTHORIZATION ai_worker;
CREATE SCHEMA IF NOT EXISTS ai_runtime AUTHORIZATION ai_worker;
ALTER SCHEMA ai_index OWNER TO ai_worker;
ALTER SCHEMA ai_runtime OWNER TO ai_worker;

-- 5. ai_collab_app 与 PUBLIC 不得访问 AI schema（不授 USAGE）。
REVOKE ALL ON SCHEMA ai_index, ai_runtime FROM PUBLIC, ai_collab_app;
REVOKE ALL ON ALL TABLES IN SCHEMA ai_index FROM PUBLIC, ai_collab_app;
REVOKE ALL ON ALL TABLES IN SCHEMA ai_runtime FROM PUBLIC, ai_collab_app;
REVOKE ALL ON ALL SEQUENCES IN SCHEMA ai_index FROM PUBLIC, ai_collab_app;
REVOKE ALL ON ALL SEQUENCES IN SCHEMA ai_runtime FROM PUBLIC, ai_collab_app;

-- 6. ai_worker 不得读写 public 业务对象。
-- pgvector 的类型、运算符与函数在 public，需要 public 的 USAGE 才能解析；该 USAGE 来自 PUBLIC
-- 伪角色的默认授权，单独对 ai_worker REVOKE 无效，这里保留它，靠"无任何表/序列权限 + 无 CREATE"隔离。
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
REVOKE CREATE ON SCHEMA public FROM ai_worker;
REVOKE ALL ON ALL TABLES IN SCHEMA public FROM ai_worker;
REVOKE ALL ON ALL SEQUENCES IN SCHEMA public FROM ai_worker;

-- 7. 连接与默认解析路径：AI schema 优先，public 仅用于解析 vector 扩展对象。
SELECT format('GRANT CONNECT ON DATABASE %I TO ai_worker', current_database()) \gexec
ALTER ROLE ai_worker SET search_path = ai_index, ai_runtime, public;

\echo db-init: extensions, ai_collab_app (business owner), ai_worker and ai_index/ai_runtime are in place
