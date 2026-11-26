package com.shitulelv.aicollab.deployment;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 契约 C0.1：业务角色 ai_collab_app 为非超级用户，拥有业务 schema，且对 ai_index / ai_runtime 无任何权限。
 * 反向：ai_worker 读写不到 public 业务表、不能在 public 建表，只在 ai_index 下建表读写（vector 可解析），
 * 且 ai_worker 新建的 ai_index 表对 ai_collab_app 没有任何权限。
 *
 * <p>直接执行部署侧正式脚本 {@code ai-collab-deploy/postgres/db-init.sql}（依赖 psql 的 {@code \getenv}/{@code \gexec}，
 * 只能用容器内 psql 跑，不能走 JDBC），再以 ai_collab_app 跑全部 Flyway 迁移并做越权断言。
 * 同时证明：现有迁移无需超级用户即可执行（扩展由 db-init 预装）；db-init 可重复执行。</p>
 */
@Testcontainers(disabledWithoutDocker = true)
class AppRoleIsolationPostgresTest {
    private static final Path DB_INIT = Path.of("../ai-collab-deploy/postgres/db-init.sql");
    private static final String DB_INIT_IN_CONTAINER = "/tmp/db-init.sql";
    // db-init 要求两个口令都不少于 16 个字符。
    private static final String APP_PW = "c01-app-" + UUID.randomUUID();
    private static final String WORKER_PW = "c01-worker-" + UUID.randomUUID();

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg17")
            .withCopyFileToContainer(MountableFile.forHostPath(DB_INIT.toAbsolutePath()), DB_INIT_IN_CONTAINER);

    private static JdbcTemplate app;
    private static JdbcTemplate worker;
    private static JdbcTemplate superuser;
    private static ExecResult firstInit;
    private static ExecResult secondInit;

    @BeforeAll
    static void provisionRolesAndMigrateAsApp() throws Exception {
        firstInit = runDbInit();
        superuser = new JdbcTemplate(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        // 越权探测表：由超级用户建在 AI schema 下并转给 ai_worker（db-init 本身不建表）。
        for (String schema : new String[] {"ai_index", "ai_runtime"}) {
            superuser.execute("CREATE TABLE " + schema + ".isolation_probe (id integer PRIMARY KEY)");
            superuser.execute("ALTER TABLE " + schema + ".isolation_probe OWNER TO ai_worker");
        }

        DriverManagerDataSource appDs = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), "ai_collab_app", APP_PW);
        Flyway.configure().dataSource(appDs).locations("classpath:db/migration").load().migrate();
        app = new JdbcTemplate(appDs);
        worker = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "ai_worker", WORKER_PW));
        // 迁移完成后再跑一次：覆盖"已有业务对象 + 已存在角色/schema"的幂等路径。
        secondInit = runDbInit();
    }

    /** 以容器超级用户执行 db-init；口令只经环境变量传给 psql，由脚本 \getenv 读取。 */
    private static ExecResult runDbInit() throws Exception {
        return POSTGRES.execInContainer("env",
                "APP_DB_PASSWORD=" + APP_PW, "AI_WORKER_DB_PASSWORD=" + WORKER_PW,
                "psql", "-v", "ON_ERROR_STOP=1", "-U", POSTGRES.getUsername(),
                "-d", POSTGRES.getDatabaseName(), "-f", DB_INIT_IN_CONTAINER);
    }

    @Test
    void dbInitSucceedsTwiceWithoutLeakingPasswords() {
        for (ExecResult run : new ExecResult[] {firstInit, secondInit}) {
            assertThat(run.getExitCode()).as("db-init exit code; stderr=%s", run.getStderr()).isZero();
            assertThat(run.getStdout() + run.getStderr())
                    .doesNotContain(APP_PW).doesNotContain(WORKER_PW);
        }
        assertThat(secondInit.getStdout()).contains("db-init:");
    }

    @Test
    void appRoleIsNotSuperuserAndOwnsBusinessSchema() {
        assertThat(app.queryForObject("SELECT current_user", String.class)).isEqualTo("ai_collab_app");
        assertThat(app.queryForObject("SELECT rolsuper FROM pg_roles WHERE rolname = current_user", Boolean.class))
                .isFalse();
        assertThat(app.queryForObject("SELECT rolbypassrls FROM pg_roles WHERE rolname = current_user", Boolean.class))
                .isFalse();
        assertThat(app.queryForObject(
                "SELECT pg_get_userbyid(nspowner) FROM pg_namespace WHERE nspname = 'public'", String.class))
                .isEqualTo("ai_collab_app");
    }

    @Test
    void appRoleCanReadAndWriteBusinessTables() {
        UUID id = UUID.randomUUID();
        String username = "c01-" + id.toString().substring(0, 8);
        assertThat(app.update("INSERT INTO app_user(id, username, password_hash, display_name) VALUES (?, ?, 'test-hash', '越权测试')",
                id, username)).isEqualTo(1);
        assertThat(app.queryForObject("SELECT username FROM app_user WHERE id = ?", String.class, id))
                .isEqualTo(username);
        assertThat(app.update("UPDATE app_user SET display_name = '已更新' WHERE id = ?", id)).isEqualTo(1);
        assertThat(app.update("DELETE FROM app_user WHERE id = ?", id)).isEqualTo(1);
    }

    @Test
    void appRoleCannotSelectFromAiSchemas() {
        for (String schema : new String[] {"ai_index", "ai_runtime"}) {
            assertPermissionDenied("SELECT count(*) FROM " + schema + ".isolation_probe");
        }
    }

    @Test
    void appRoleCannotCreateTablesInAiSchemas() {
        for (String schema : new String[] {"ai_index", "ai_runtime"}) {
            assertPermissionDenied("CREATE TABLE " + schema + ".app_intrusion (id integer)");
        }
    }

    @Test
    void workerRoleIsNotSuperuserAndOwnsAiSchemas() {
        assertThat(worker.queryForObject("SELECT current_user", String.class)).isEqualTo("ai_worker");
        assertThat(worker.queryForObject("SELECT rolsuper FROM pg_roles WHERE rolname = current_user", Boolean.class))
                .isFalse();
        for (String schema : new String[] {"ai_index", "ai_runtime"}) {
            assertThat(worker.queryForObject(
                    "SELECT pg_get_userbyid(nspowner) FROM pg_namespace WHERE nspname = ?", String.class, schema))
                    .isEqualTo("ai_worker");
        }
    }

    @Test
    void workerRoleCannotReadOrWriteBusinessTables() {
        assertPermissionDenied(worker, "SELECT count(*) FROM public.app_user");
        assertPermissionDenied(worker, "INSERT INTO public.app_user(id, username, password_hash, display_name) "
                + "VALUES ('00000000-0000-0000-0000-000000000042', 'worker-intrusion', 'x', 'x')");
        assertPermissionDenied(worker, "UPDATE public.app_user SET display_name = 'x'");
        assertPermissionDenied(worker, "DELETE FROM public.app_user");
    }

    @Test
    void workerRoleCannotCreateTablesInPublic() {
        assertPermissionDenied(worker, "CREATE TABLE public.worker_intrusion (id integer)");
    }

    @Test
    void workerRoleCanCreateReadAndWriteVectorTablesInAiIndex() {
        String table = "ai_index.worker_probe_" + suffix();
        worker.execute("CREATE TABLE " + table + " (id integer PRIMARY KEY, embedding vector(3) NOT NULL)");
        try {
            assertThat(worker.update("INSERT INTO " + table + " VALUES (1, '[1,2,3]'), (2, '[3,2,1]')"))
                    .isEqualTo(2);
            assertThat(worker.queryForObject("SELECT format_type(atttypid, atttypmod) FROM pg_attribute "
                    + "WHERE attrelid = ?::text::regclass AND attname = 'embedding'", String.class, table))
                    .isEqualTo("vector(3)");
            assertThat(worker.queryForObject("SELECT id FROM " + table
                    + " ORDER BY embedding <-> '[1,2,2]'::vector LIMIT 1", Integer.class)).isEqualTo(1);
            assertThat(worker.update("UPDATE " + table + " SET embedding = '[0,0,1]' WHERE id = 2")).isEqualTo(1);
            assertThat(worker.update("DELETE FROM " + table + " WHERE id = 1")).isEqualTo(1);
            assertThat(worker.queryForObject("SELECT count(*) FROM " + table, Integer.class)).isEqualTo(1);
        } finally {
            worker.execute("DROP TABLE " + table);
        }
    }

    @Test
    void appRoleHasNoPrivilegeOnWorkerCreatedAiIndexTable() {
        String table = "ai_index.worker_owned_" + suffix();
        worker.execute("CREATE TABLE " + table + " (id integer PRIMARY KEY, embedding vector(3))");
        worker.update("INSERT INTO " + table + " VALUES (1, '[1,1,1]')");
        try {
            assertPermissionDenied("SELECT count(*) FROM " + table);
            assertPermissionDenied("INSERT INTO " + table + " VALUES (2, '[2,2,2]')");
            assertPermissionDenied("UPDATE " + table + " SET id = 3");
            assertPermissionDenied("DELETE FROM " + table);
            assertPermissionDenied("TRUNCATE " + table);
            assertPermissionDenied("DROP TABLE " + table);
            // 目录级核对：以超级用户查询 ai_collab_app 的有效权限（含经 PUBLIC 继承的权限）。
            for (String privilege : new String[] {"SELECT", "INSERT", "UPDATE", "DELETE", "TRUNCATE", "REFERENCES",
                    "TRIGGER"}) {
                assertThat(superuser.queryForObject("SELECT has_table_privilege('ai_collab_app', ?, ?)",
                        Boolean.class, table, privilege)).as(privilege + " on " + table).isFalse();
            }
            for (String privilege : new String[] {"USAGE", "CREATE"}) {
                assertThat(superuser.queryForObject("SELECT has_schema_privilege('ai_collab_app', 'ai_index', ?)",
                        Boolean.class, privilege)).as(privilege + " on ai_index").isFalse();
            }
        } finally {
            worker.execute("DROP TABLE " + table);
        }
    }

    private static String suffix() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private static void assertPermissionDenied(String statement) {
        assertPermissionDenied(app, statement);
    }

    private static void assertPermissionDenied(JdbcTemplate db, String statement) {
        assertThatThrownBy(() -> db.execute(statement))
                .as(statement)
                .satisfies(error -> {
                    SQLException sql = rootSqlException(error);
                    // SQLException 实现了 Iterable，转成 Throwable 以消除 assertThat 重载歧义。
                    assertThat((Throwable) sql).as("SQLException for " + statement).isNotNull();
                    assertThat(sql.getSQLState()).isEqualTo("42501");
                    assertThat(sql.getMessage()).contains("permission denied");
                    // 安全：报错中不得出现任何口令原文。
                    assertThat(String.valueOf(error.getMessage()))
                            .doesNotContain(APP_PW).doesNotContain(WORKER_PW);
                });
    }

    private static SQLException rootSqlException(Throwable error) {
        SQLException found = null;
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql) {
                found = sql;
            }
        }
        return found;
    }
}
