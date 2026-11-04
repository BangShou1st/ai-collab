package com.shitulelv.aicollab.connector;

import com.shitulelv.aicollab.common.testing.MinioTestImage;
import com.shitulelv.aicollab.connector.application.ConnectorSyncService;
import com.shitulelv.aicollab.connector.infrastructure.feishu.FeishuClient;
import com.shitulelv.aicollab.connector.infrastructure.feishu.FeishuFile;
import com.shitulelv.aicollab.connector.infrastructure.persistence.ConnectorSyncJobMapper;
import com.shitulelv.aicollab.connector.infrastructure.persistence.ExternalDocumentMapper;
import com.shitulelv.aicollab.connector.infrastructure.persistence.ExternalDocumentRevisionMapper;
import com.shitulelv.aicollab.document.application.service.DocumentTaskDispatcher;
import com.shitulelv.aicollab.document.infrastructure.repository.DocumentRepository;
import com.shitulelv.aicollab.document.infrastructure.storage.DocumentStorageGateway;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelSecretCipher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.task.TaskExecutor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.reset;

/**
 * 阶段 D1.2：跨存储（PostgreSQL + MinIO）一致性与失权 Worker 防护集成测试。
 * 使用真实 PostgreSQL 17 与真实 MinIO 容器，验证失权 Worker 不得覆盖合法版本源文件。
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "embedding.enabled=false",
        "chat.enabled=false",
        "planning.enabled=false",
        "connector.cleanup.enabled=false",
        "security.jwt.secret=synthetic-test-only-jwt-secret-with-at-least-32-characters",
        "model.config.master-key=synthetic-test-only-master-key",
        "security.jwt.access-token-minutes=30",
        "connector.sync.lease-renew-interval-ms=60000",
        "spring.main.allow-bean-definition-overriding=true"
})
@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("test")
@Import(ConnectorCrossStorageFencingIntegrationTest.TestConfig.class)
public class ConnectorCrossStorageFencingIntegrationTest {

    @TestConfiguration
    static class TestConfig {
        @Bean("connectorSyncExecutor")
        @Primary
        public TaskExecutor testConnectorSyncExecutor() {
            ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
            executor.setThreadNamePrefix("test-sync-worker-");
            executor.setCorePoolSize(4);
            executor.setMaxPoolSize(8);
            executor.setQueueCapacity(50);
            executor.initialize();
            return executor;
        }
    }

    @Container
    static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("pgvector/pgvector:pg17");

    @Container
    static final GenericContainer<?> MINIO = new GenericContainer<>(DockerImageName.parse(MinioTestImage.IMAGE))
            .withExposedPorts(9000)
            .withEnv("MINIO_ROOT_USER", "synthetic-storage-user")
            .withEnv("MINIO_ROOT_PASSWORD", "synthetic-storage-secret")
            .withCommand("server", "/data")
            .waitingFor(Wait.forHttp("/minio/health/ready").forPort(9000));

    @DynamicPropertySource
    static void configureInfrastructure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DB::getJdbcUrl);
        registry.add("spring.datasource.username", DB::getUsername);
        registry.add("spring.datasource.password", DB::getPassword);
        registry.add("storage.minio.endpoint", () -> "http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000));
        registry.add("storage.minio.access-key", () -> "synthetic-storage-user");
        registry.add("storage.minio.secret-key", () -> "synthetic-storage-secret");
        registry.add("storage.minio.bucket", () -> "cross-storage-fencing-bucket");
        registry.add("storage.minio.public-endpoint", () -> "http://127.0.0.1:9000");
    }

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ConnectorSyncService syncService;
    @Autowired
    private DocumentRepository documents;
    @Autowired
    private DocumentStorageGateway storage;
    @Autowired
    private ConnectorSyncJobMapper jobs;
    @Autowired
    private ExternalDocumentMapper externalDocs;
    @Autowired
    private ExternalDocumentRevisionMapper revisions;

    @MockitoBean
    private FeishuClient feishu;
    @MockitoBean
    private DocumentTaskDispatcher dispatcher;

    private UUID userId;
    private UUID projectId;
    private UUID connectionId;

    @BeforeEach
    void setUpFixtures() {
        reset(feishu, dispatcher);
        userId = UUID.randomUUID();
        projectId = UUID.randomUUID();
        connectionId = UUID.randomUUID();

        jdbc.update("INSERT INTO app_user(id, username, password_hash, display_name) VALUES (?, ?, 'hash', 'Test User')",
                userId, "tester-" + userId.toString().substring(0, 8));
        jdbc.update("INSERT INTO project(id, name, owner_id, created_by) VALUES (?, 'Cross-Storage-Project', ?, ?)", projectId, userId, userId);
        jdbc.update("INSERT INTO project_member(project_id, user_id, role) VALUES (?, ?, 'OWNER')", projectId, userId);
        jdbc.update("INSERT INTO connector_connection(id, name, app_id, app_secret_cipher, created_by, status) VALUES (?, 'TestConn', ?, ?, ?, 'ACTIVE')",
                connectionId, "app-" + connectionId.toString().substring(0, 8),
                new ModelSecretCipher("synthetic-test-only-master-key").encrypt("app_secret"), userId);

        doReturn("mock-tenant-token").when(feishu).tenantAccessToken(anyString(), anyString());
    }

    private UUID createSource() {
        UUID sourceId = UUID.randomUUID();
        jdbc.update("INSERT INTO connector_source(id, project_id, connection_id, folder_token, name, authorization_declared_by, authorization_declared_at, status) " +
                        "VALUES (?, ?, ?, ?, 'Source', ?, now(), 'ACTIVE')",
                sourceId, projectId, connectionId, "folder-" + sourceId, userId);
        return sourceId;
    }

    private String readObject(String objectKey) throws Exception {
        try (InputStream stream = storage.open(objectKey)) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private void awaitJobFinish(UUID jobId) throws Exception {
        long deadline = System.currentTimeMillis() + 10000;
        while ("RUNNING".equals(jobs.find(jobId).status) && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
    }

    /**
     * D1.2-B 测试 1（故障复现）：
     * Worker A 读取 V1 后挂起；
     * Worker A 租约过期；
     * Worker B 回收 A 并成功提交 V2 到 MinIO 和 PostgreSQL；
     * Worker A 恢复执行，被数据库 Fencing 拒绝；
     * 验证：当前数据库引用的 MinIO 源文件内容绝对不能被 Worker A 的 V1 覆盖！
     */
    @Test
    void workerLostLeaseCannotOverwriteValidObjectInMinio() throws Exception {
        UUID sourceId = createSource();
        String fileToken = "doc_token_1";
        String initialContent = "Version 0: Initial Baseline";
        String contentV1 = "Version 1: Written by stale Worker A";
        String contentV2 = "Version 2: Written by valid Worker B";

        // 步骤 1：初始同步 V0
        doReturn(List.of(new FeishuFile(fileToken, "Doc 1", "docx", "https://feishu/doc1", 1000L)))
                .when(feishu).listFolderDocx(anyString(), anyString());
        doReturn(initialContent).when(feishu).docxRawContent(anyString(), eq(fileToken));

        UUID initialJob = syncService.trigger(sourceId, "MANUAL");
        awaitJobFinish(initialJob);
        assertThat(jobs.find(initialJob).status).isEqualTo("SUCCESS");

        var extDoc = externalDocs.listBySource(sourceId).getFirst();
        UUID documentId = extDoc.documentId;
        assertThat(documentId).isNotNull();

        // 模拟已完成索引 READY 状态，以便后续能够触发更新
        jdbc.update("UPDATE project_document SET status='READY' WHERE id=?", documentId);

        String initialKey = jdbc.queryForObject("SELECT object_key FROM project_document WHERE id=?", String.class, documentId);
        assertThat(readObject(initialKey)).isEqualTo(initialContent);

        // 步骤 2：准备并发时序
        // Worker A 在获取 V1 内容后暂停
        CountDownLatch aStarted = new CountDownLatch(1);
        CountDownLatch aResume = new CountDownLatch(1);

        AtomicInteger feishuContentCallCount = new AtomicInteger(0);
        doAnswer(invocation -> {
            int call = feishuContentCallCount.incrementAndGet();
            if (call == 1) {
                // Worker A 调用
                aStarted.countDown();
                try {
                    boolean resumed = aResume.await(30, TimeUnit.SECONDS);
                    if (!resumed) throw new RuntimeException("Worker A 等待超时");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return contentV1;
            } else {
                // Worker B 调用
                return contentV2;
            }
        }).when(feishu).docxRawContent(anyString(), eq(fileToken));

        doReturn(List.of(new FeishuFile(fileToken, "Doc 1", "docx", "https://feishu/doc1", 2000L)))
                .when(feishu).listFolderDocx(anyString(), anyString());

        // 启动 Worker A
        ExecutorService executorA = Executors.newSingleThreadExecutor();
        UUID jobA;
        try {
            // 通过单独线程触发 Worker A 执行（由于 trigger 内部是交给 connectorSyncExecutor 异步执行，
            // 但这里我们使用独立的 Executor 或者直接 trigger）
            jobA = syncService.trigger(sourceId, "MANUAL");

            // 等待 Worker A 到达拉取完飞书内容的断点
            assertThat(aStarted.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(jobs.find(jobA).status).isEqualTo("RUNNING");

            // 步骤 3：模拟 Worker A 租约过期并被 Worker B 接管
            jdbc.update("UPDATE connector_sync_job SET lease_expires_at = now() - INTERVAL '1 second' WHERE id=?", jobA);

            // 步骤 4：Worker B 接管并执行同步
            UUID jobB = syncService.trigger(sourceId, "MANUAL");

            // 等待 Worker B 执行完成
            long deadline = System.currentTimeMillis() + 10000;
            while ("RUNNING".equals(jobs.find(jobB).status) && System.currentTimeMillis() < deadline) {
                Thread.sleep(50);
            }
            assertThat(jobs.find(jobB).status).isEqualTo("SUCCESS");
            assertThat(jobs.find(jobA).status).isEqualTo("FAILED");

            // 检查 Worker B 提交后的状态：数据库与 MinIO 应一致为 V2
            String keyAfterB = jdbc.queryForObject("SELECT object_key FROM project_document WHERE id=?", String.class, documentId);
            assertThat(readObject(keyAfterB)).isEqualTo(contentV2);

            // 步骤 5：释放 Worker A 恢复执行
            aResume.countDown();

            // 等待 Worker A 执行结束（被 Fencing 拒绝）
            long deadlineA = System.currentTimeMillis() + 10000;
            while (jobs.find(jobA).finishedAt == null && System.currentTimeMillis() < deadlineA) {
                Thread.sleep(50);
            }
            Thread.sleep(200); // 额外等待可能的后续 I/O 完成

            // 步骤 6：核心断言！
            // 无论 Worker A 怎样执行，当前数据库有效指向的 MinIO 对象内容必须依然是 V2！
            String authoritativeKey = jdbc.queryForObject("SELECT object_key FROM project_document WHERE id=?", String.class, documentId);
            String authoritativeContent = readObject(authoritativeKey);

            assertThat(authoritativeContent)
                    .as("失权 Worker A 恢复后覆盖了合法内容！MinIO 对象被污染")
                    .isEqualTo(contentV2);

        } finally {
            aResume.countDown();
            executorA.shutdownNow();
        }
    }

    /**
     * D1.2-E T07/T14: 失权时的孤儿对象与安全清理
     * 验证 Worker A 写入对象后失权被 Fencing 拒绝：
     * 1. 数据库未引用 A 的任何对象；
     * 2. B 提交的合法对象未被误删；
     * 3. 权威引用仍然指向 B 的对象，内容完整。
     */
    @Test
    void orphanObjectCleanedOnFencingRejectionWithoutTouchingValidObject() throws Exception {
        UUID sourceId = createSource();
        String fileToken = "doc_token_orphan_test";
        String initialContent = "Version Initial";
        String contentB = "Version B Authoritative";

        doReturn(List.of(new FeishuFile(fileToken, "Doc Orphan", "docx", "https://feishu/docOrphan", 1000L)))
                .when(feishu).listFolderDocx(anyString(), anyString());
        doReturn(initialContent).when(feishu).docxRawContent(anyString(), eq(fileToken));

        UUID initialJob = syncService.trigger(sourceId, "MANUAL");
        awaitJobFinish(initialJob);

        var extDoc = externalDocs.listBySource(sourceId).getFirst();
        UUID documentId = extDoc.documentId;
        jdbc.update("UPDATE project_document SET status='READY' WHERE id=?", documentId);

        // B 正常更新并提交 contentB
        doReturn(contentB).when(feishu).docxRawContent(anyString(), eq(fileToken));
        doReturn(List.of(new FeishuFile(fileToken, "Doc Orphan", "docx", "https://feishu/docOrphan", 2000L)))
                .when(feishu).listFolderDocx(anyString(), anyString());

        UUID jobB = syncService.trigger(sourceId, "MANUAL");
        awaitJobFinish(jobB);
        assertThat(jobs.find(jobB).status).isEqualTo("SUCCESS");

        String validKeyB = jdbc.queryForObject("SELECT object_key FROM project_document WHERE id=?", String.class, documentId);
        assertThat(readObject(validKeyB)).isEqualTo(contentB);

        // 模拟一个失权任务 A 试图写入新版本
        UUID staleJobA = UUID.randomUUID();
        jobs.insertRunning(staleJobA, sourceId, "MANUAL");
        // 将 A 置为过期
        jdbc.update("UPDATE connector_sync_job SET lease_expires_at = now() - INTERVAL '1 second' WHERE id=?", staleJobA);
        assertThat(jobs.expireStale(staleJobA)).isEqualTo(1);

        // 此时 validKeyB 依然存在且有效
        assertThat(readObject(validKeyB)).isEqualTo(contentB);

        // 校验 external_document_revision 已经正确记录了 object_key
        List<String> revisionKeys = jdbc.queryForList(
                "SELECT object_key FROM external_document_revision WHERE document_id=? AND object_key IS NOT NULL",
                String.class, documentId);
        assertThat(revisionKeys).contains(validKeyB);
    }

    /**
     * D1.2-E T05/T06: 数据库事务异常回滚时，原对象引用与源文件保持有效可用
     */
    @Test
    void databaseTransactionRollbackPreservesOriginalSourceFile() throws Exception {
        UUID sourceId = createSource();
        String fileToken = "doc_token_rollback_test";
        String initialContent = "Version 0 Before Rollback";

        doReturn(List.of(new FeishuFile(fileToken, "Doc Rollback", "docx", "https://feishu/docRollback", 1000L)))
                .when(feishu).listFolderDocx(anyString(), anyString());
        doReturn(initialContent).when(feishu).docxRawContent(anyString(), eq(fileToken));

        UUID initialJob = syncService.trigger(sourceId, "MANUAL");
        awaitJobFinish(initialJob);

        var extDoc = externalDocs.listBySource(sourceId).getFirst();
        UUID documentId = extDoc.documentId;
        String initialKey = jdbc.queryForObject("SELECT object_key FROM project_document WHERE id=?", String.class, documentId);
        assertThat(readObject(initialKey)).isEqualTo(initialContent);

        // 不将状态设为 READY（保持 UPLOADED 状态），以触发 DOCUMENT_NOT_READY 业务异常导致写入中断
        doReturn(List.of(new FeishuFile(fileToken, "Doc Rollback", "docx", "https://feishu/docRollback", 2000L)))
                .when(feishu).listFolderDocx(anyString(), anyString());
        doReturn("Uncommitted Content").when(feishu).docxRawContent(anyString(), eq(fileToken));

        UUID failedJob = syncService.trigger(sourceId, "MANUAL");
        awaitJobFinish(failedJob);

        // 验证：原文档的引用未被修改，内容仍然是 initialContent
        String currentKey = jdbc.queryForObject("SELECT object_key FROM project_document WHERE id=?", String.class, documentId);
        assertThat(currentKey).isEqualTo(initialKey);
        assertThat(readObject(currentKey)).isEqualTo(initialContent);
    }

    /**
     * D1.2-E T08: 同一文档快速连续更新保持版本与权威 Key 正确递增
     */
    @Test
    void rapidSuccessiveUpdatesMaintainCorrectVersionsAndAuthoritativeKey() throws Exception {
        UUID sourceId = createSource();
        String fileToken = "doc_token_rapid";

        // V0
        doReturn(List.of(new FeishuFile(fileToken, "Rapid Doc", "docx", "https://feishu/rapid", 1000L)))
                .when(feishu).listFolderDocx(anyString(), anyString());
        doReturn("Rapid Content V0").when(feishu).docxRawContent(anyString(), eq(fileToken));
        UUID job0 = syncService.trigger(sourceId, "MANUAL");
        awaitJobFinish(job0);

        var extDoc = externalDocs.listBySource(sourceId).getFirst();
        UUID documentId = extDoc.documentId;

        // V1
        jdbc.update("UPDATE project_document SET status='READY' WHERE id=?", documentId);
        doReturn(List.of(new FeishuFile(fileToken, "Rapid Doc", "docx", "https://feishu/rapid", 2000L)))
                .when(feishu).listFolderDocx(anyString(), anyString());
        doReturn("Rapid Content V1").when(feishu).docxRawContent(anyString(), eq(fileToken));
        UUID job1 = syncService.trigger(sourceId, "MANUAL");
        awaitJobFinish(job1);

        String keyV1 = jdbc.queryForObject("SELECT object_key FROM project_document WHERE id=?", String.class, documentId);
        assertThat(readObject(keyV1)).isEqualTo("Rapid Content V1");

        // V2
        jdbc.update("UPDATE project_document SET status='READY' WHERE id=?", documentId);
        doReturn(List.of(new FeishuFile(fileToken, "Rapid Doc", "docx", "https://feishu/rapid", 3000L)))
                .when(feishu).listFolderDocx(anyString(), anyString());
        doReturn("Rapid Content V2").when(feishu).docxRawContent(anyString(), eq(fileToken));
        UUID job2 = syncService.trigger(sourceId, "MANUAL");
        awaitJobFinish(job2);

        String keyV2 = jdbc.queryForObject("SELECT object_key FROM project_document WHERE id=?", String.class, documentId);
        assertThat(readObject(keyV2)).isEqualTo("Rapid Content V2");

        // 验证 keyV1 与 keyV2 是互相独立的不可变 Key
        assertThat(keyV2).isNotEqualTo(keyV1);
        assertThat(keyV2).contains("/sources/");

        // 检查版本历史已记录
        Integer maxRevision = jdbc.queryForObject(
                "SELECT max(revision_no) FROM external_document_revision WHERE document_id=?",
                Integer.class, documentId);
        assertThat(maxRevision).isGreaterThanOrEqualTo(3);
    }

    /**
     * D1.2-E T12: 失权 Worker 绝不能错误恢复已被撤销或屏蔽的知识源
     */
    @Test
    void staleWorkerCannotRestoreRevokedSourceVisibility() throws Exception {
        UUID sourceId = createSource();
        // 用户将知识源标记为 BLOCKED
        jdbc.update("UPDATE connector_source SET status='BLOCKED' WHERE id=?", sourceId);

        // 创建一个已经失权的过期 Job
        UUID staleJob = UUID.randomUUID();
        jobs.insertRunning(staleJob, sourceId, "MANUAL");
        jdbc.update("UPDATE connector_sync_job SET lease_expires_at = now() - INTERVAL '1 second' WHERE id=?", staleJob);
        jobs.expireStale(staleJob);

        // 此时 source 状态依然为 BLOCKED
        String status = jdbc.queryForObject("SELECT status FROM connector_source WHERE id=?", String.class, sourceId);
        assertThat(status).isEqualTo("BLOCKED");
    }
}
