package com.shitulelv.aicollab.deployment;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 契约 §9.2 第 1 条数据库角色隔离测试（需要 Docker，本机不自动启动 Docker）：
 * <ul>
 *   <li>{@code ai_collab_app} 可正常跑通 Flyway 并读写 {@code public.project_document}，但被拒绝读写 {@code ai_index.chunk}；</li>
 *   <li>{@code ai_worker} 可建表并读写 {@code ai_index.chunk}，但被拒绝读写 {@code public.project_document} 与在 {@code public} 建表。</li>
 * </ul>
 */
@Testcontainers(disabledWithoutDocker = true)
class P2DatabaseRoleIsolationPostgresTest {

    private static final String APP_PW = "p2_app_isolation_pw";
    private static final String WORKER_PW = "p2_worker_isolation_pw";
    private static final String SQLSTATE_INSUFFICIENT_PRIVILEGE = "42501";

    @org.testcontainers.junit.jupiter.Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg17");

    @Test
    void enforcesStrictCrossSchemaIsolationBetweenAppAndAiWorker() throws Exception {
        Path dbInitSql = resolveRepoFile(
                "../ai-collab-deploy/postgres/db-init.sql",
                "ai-collab-deploy/postgres/db-init.sql");
        Path aiIndexSchemaSql = resolveRepoFile(
                "../ai-collab-ai-worker/src/ai_collab_worker/index/sql/0001_ai_index_schema.sql",
                "ai-collab-ai-worker/src/ai_collab_worker/index/sql/0001_ai_index_schema.sql");

        POSTGRES.copyFileToContainer(MountableFile.forHostPath(dbInitSql), "/tmp/db-init.sql");
        POSTGRES.copyFileToContainer(MountableFile.forHostPath(aiIndexSchemaSql), "/tmp/0001_ai_index_schema.sql");

        Container.ExecResult initResult = POSTGRES.execInContainer(
                "env",
                "PGPASSWORD=" + POSTGRES.getPassword(),
                "APP_DB_PASSWORD=" + APP_PW,
                "AI_WORKER_DB_PASSWORD=" + WORKER_PW,
                "psql",
                "-v", "ON_ERROR_STOP=1",
                "-U", POSTGRES.getUsername(),
                "-d", POSTGRES.getDatabaseName(),
                "-f", "/tmp/db-init.sql");
        assertThat(initResult.getExitCode())
                .withFailMessage("db-init.sql failed: stdout=%s stderr=%s",
                        initResult.getStdout(), initResult.getStderr())
                .isZero();

        DriverManagerDataSource appDataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), "ai_collab_app", APP_PW);
        Flyway.configure()
                .dataSource(appDataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();

        Container.ExecResult workerSchemaResult = POSTGRES.execInContainer(
                "env",
                "PGPASSWORD=" + WORKER_PW,
                "psql",
                "-v", "ON_ERROR_STOP=1",
                "-U", "ai_worker",
                "-d", POSTGRES.getDatabaseName(),
                "-f", "/tmp/0001_ai_index_schema.sql");
        assertThat(workerSchemaResult.getExitCode())
                .withFailMessage("0001_ai_index_schema.sql failed: stdout=%s stderr=%s",
                        workerSchemaResult.getStdout(), workerSchemaResult.getStderr())
                .isZero();

        JdbcTemplate app = new JdbcTemplate(appDataSource);
        JdbcTemplate worker = new JdbcTemplate(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), "ai_worker", WORKER_PW));

        // 双方在各自 schema 正常读写样本行
        UUID userId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        app.update(
                "INSERT INTO app_user(id,username,password_hash,display_name) VALUES (?,?,'unused','iso')",
                userId, "iso-" + userId.toString().substring(0, 8));
        app.update(
                "INSERT INTO project(id,name,owner_id,created_by) VALUES (?,'iso',?,?)",
                projectId, userId, userId);
        app.update(
                "INSERT INTO project_document(id,project_id,display_name,original_filename,mime_type,size_bytes,"
                        + "object_key,status,uploaded_by) VALUES (?,?,'doc','doc.txt','text/plain',1,?,?,?)",
                documentId, projectId, "iso/" + documentId, "READY", userId);
        assertThat(app.queryForObject(
                "SELECT count(*) FROM public.project_document WHERE id = ?",
                Integer.class, documentId)).isOne();

        UUID generationId = UUID.randomUUID();
        UUID snapshotId = UUID.randomUUID();
        UUID aiChunkId = UUID.randomUUID();
        String hash = "a".repeat(64);
        worker.update(
                "INSERT INTO ai_index.index_generation(id,embedding_model,dimension,status) VALUES (?,'qwen3',1024,'BUILDING')",
                generationId);
        worker.update(
                "INSERT INTO ai_index.chunk(id,generation_id,project_id,document_id,body_snapshot_id,chunk_no,"
                        + "chunk_type,heading,content,content_hash,metadata,embedding) "
                        + "VALUES (?,?,?,?,?,0,'PARENT','h','parent body',?,'{}'::jsonb,NULL)",
                aiChunkId, generationId, projectId, documentId, snapshotId, hash);
        assertThat(worker.queryForObject(
                "SELECT count(*) FROM ai_index.chunk WHERE id = ?",
                Integer.class, aiChunkId)).isOne();

        // ai_collab_app 拒读写 ai_index.chunk
        assertPermissionDenied(() -> app.queryForObject("SELECT count(*) FROM ai_index.chunk", Integer.class));
        assertPermissionDenied(() -> app.update(
                "INSERT INTO ai_index.chunk(id,generation_id,project_id,document_id,body_snapshot_id,chunk_no,"
                        + "chunk_type,content,content_hash) VALUES (?,?,?,?,?,1,'PARENT','x',?)",
                UUID.randomUUID(), generationId, projectId, documentId, snapshotId, hash));
        assertPermissionDenied(() -> app.update("UPDATE ai_index.chunk SET content = 'tampered' WHERE id = ?", aiChunkId));
        assertPermissionDenied(() -> app.update("DELETE FROM ai_index.chunk WHERE id = ?", aiChunkId));

        // ai_worker 拒读写 public.project_document 与在 public 建表
        assertPermissionDenied(() -> worker.queryForObject("SELECT count(*) FROM public.project_document", Integer.class));
        assertPermissionDenied(() -> worker.update(
                "INSERT INTO public.project_document(id,project_id,display_name,original_filename,mime_type,size_bytes,"
                        + "object_key,status,uploaded_by) VALUES (?,?,'x','x.txt','text/plain',1,?,'READY',?)",
                UUID.randomUUID(), projectId, "iso/forbid-" + UUID.randomUUID(), userId));
        assertPermissionDenied(() -> worker.update(
                "UPDATE public.project_document SET status = 'FAILED' WHERE id = ?", documentId));
        assertPermissionDenied(() -> worker.update(
                "DELETE FROM public.project_document WHERE id = ?", documentId));
        assertPermissionDenied(() -> worker.execute("CREATE TABLE public.worker_forbidden_probe(id integer)"));
    }

    private static void assertPermissionDenied(Runnable sqlAction) {
        assertThatThrownBy(sqlAction::run)
                .isInstanceOf(DataAccessException.class)
                .satisfies(ex -> {
                    PSQLException psql = findCause(ex, PSQLException.class);
                    assertThat((Throwable) psql).isNotNull();
                    assertThat(psql.getSQLState()).isEqualTo(SQLSTATE_INSUFFICIENT_PRIVILEGE);
                    assertThat(psql.getMessage()).containsIgnoringCase("permission denied");
                });
    }

    private static <T extends Throwable> T findCause(Throwable root, Class<T> type) {
        Throwable cursor = root;
        while (cursor != null) {
            if (type.isInstance(cursor)) {
                return type.cast(cursor);
            }
            cursor = cursor.getCause();
        }
        return null;
    }

    private static Path resolveRepoFile(String relativeFromModule, String relativeFromRoot) {
        Path fromModule = Path.of(relativeFromModule);
        if (Files.exists(fromModule)) {
            return fromModule.normalize();
        }
        Path fromRoot = Path.of(relativeFromRoot);
        if (Files.exists(fromRoot)) {
            return fromRoot.normalize();
        }
        throw new IllegalStateException("Required SQL fixture not found: " + relativeFromModule + " or " + relativeFromRoot);
    }
}
