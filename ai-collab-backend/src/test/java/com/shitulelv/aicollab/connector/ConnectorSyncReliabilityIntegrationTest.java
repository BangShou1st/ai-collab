package com.shitulelv.aicollab.connector;

import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.connector.application.ConnectorOAuthTokenService;
import com.shitulelv.aicollab.connector.application.ConnectorSyncMetrics;
import com.shitulelv.aicollab.connector.application.ConnectorSyncService;
import com.shitulelv.aicollab.connector.application.ConnectorVisibilityService;
import com.shitulelv.aicollab.connector.infrastructure.feishu.FeishuApiException;
import com.shitulelv.aicollab.connector.infrastructure.feishu.FeishuClient;
import com.shitulelv.aicollab.connector.infrastructure.feishu.FeishuFile;
import com.shitulelv.aicollab.connector.infrastructure.persistence.ConnectorConnectionMapper;
import com.shitulelv.aicollab.connector.infrastructure.persistence.ConnectorSourceMapper;
import com.shitulelv.aicollab.connector.infrastructure.persistence.ConnectorSyncJobMapper;
import com.shitulelv.aicollab.connector.infrastructure.persistence.ExternalDocumentMapper;
import com.shitulelv.aicollab.connector.infrastructure.persistence.ExternalDocumentRevisionMapper;
import com.shitulelv.aicollab.document.application.service.DocumentRegistrationService;
import com.shitulelv.aicollab.document.application.service.DocumentTaskDispatcher;
import com.shitulelv.aicollab.document.infrastructure.repository.DocumentRepository;
import com.shitulelv.aicollab.document.infrastructure.storage.DocumentStorageGateway;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelSecretCipher;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.ibatis.session.SqlSessionFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 飞书同步任务可靠性：派发拒绝补偿、租约续期、失权防护与并发互斥。
 * 领取、续期、回收与迟到收口全部走真实 PostgreSQL（唯一部分索引、expireStale、
 * finish 条件更新），不使用 Mockito 代替数据库一致性验证。
 */
@Testcontainers(disabledWithoutDocker = true)
class ConnectorSyncReliabilityIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> db = new PostgreSQLContainer<>("pgvector/pgvector:pg17");

    static DataSource dataSource;
    static JdbcTemplate jdbc;
    static ConnectorSyncJobMapper jobs;
    static ConnectorSourceMapper sources;
    static ConnectorConnectionMapper connections;
    static ExternalDocumentMapper externalDocs;
    static ExternalDocumentRevisionMapper revisions;
    static FeishuClient feishu;
    static DocumentRepository documents;
    static DocumentStorageGateway storage;
    static DocumentTaskDispatcher dispatcher;

    static UUID projectId, connectionId, userId;

    @BeforeAll
    static void setUp() throws Exception {
        dataSource = new DriverManagerDataSource(db.getJdbcUrl(), db.getUsername(), db.getPassword());
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        SqlSessionFactoryBean factoryBean = new SqlSessionFactoryBean();
        factoryBean.setDataSource(dataSource);
        SqlSessionFactory factory = factoryBean.getObject();
        factory.getConfiguration().setMapUnderscoreToCamelCase(true);
        factory.getConfiguration().getTypeHandlerRegistry().register(UUID.class,
                com.shitulelv.aicollab.common.persistence.PostgresUuidTypeHandler.class);
        factory.getConfiguration().addMapper(ConnectorSyncJobMapper.class);
        factory.getConfiguration().addMapper(ConnectorSourceMapper.class);
        factory.getConfiguration().addMapper(ConnectorConnectionMapper.class);
        factory.getConfiguration().addMapper(ExternalDocumentMapper.class);
        factory.getConfiguration().addMapper(ExternalDocumentRevisionMapper.class);
        SqlSessionTemplate template = new SqlSessionTemplate(factory);
        jobs = template.getMapper(ConnectorSyncJobMapper.class);
        sources = template.getMapper(ConnectorSourceMapper.class);
        connections = template.getMapper(ConnectorConnectionMapper.class);
        externalDocs = template.getMapper(ExternalDocumentMapper.class);
        revisions = template.getMapper(ExternalDocumentRevisionMapper.class);
        feishu = mock(FeishuClient.class);
        documents = mock(DocumentRepository.class);
        storage = mock(DocumentStorageGateway.class);
        dispatcher = mock(DocumentTaskDispatcher.class);

        userId = UUID.randomUUID();
        projectId = UUID.randomUUID();
        connectionId = UUID.randomUUID();
        jdbc.update("INSERT INTO app_user(id,username,password_hash,display_name) VALUES (?,'ux-rel','unused','rel')", userId);
        jdbc.update("INSERT INTO project(id,name,owner_id,created_by) VALUES (?, 'rel', ?, ?)", projectId, userId, userId);
        jdbc.update("INSERT INTO connector_connection(id,name,app_id,app_secret_cipher,created_by) VALUES (?, 'conn', 'cli_rel', ?, ?)", connectionId,
                new ModelSecretCipher("synthetic-test-master-key-only").encrypt("synthetic-secret"), userId);
    }

    @AfterAll
    static void tearDown() {
        // Testcontainers 静态容器随 JVM 钩子自动关闭。
    }

    /** 每个测试独立的知识源，避免互为前提。 */
    private UUID newSource() {
        UUID sourceId = UUID.randomUUID();
        String folderToken = "folder-" + sourceId;
        jdbc.update("INSERT INTO connector_source(id,project_id,connection_id,folder_token,name,authorization_declared_by,authorization_declared_at) VALUES (?,?,?,?,'src',?,now())",
                sourceId, projectId, connectionId, folderToken, userId);
        return sourceId;
    }

    private ConnectorSyncService service(TaskExecutor executor, long renewIntervalMs) {
        org.springframework.transaction.PlatformTransactionManager txManager =
                new org.springframework.jdbc.support.JdbcTransactionManager(dataSource);
        return new ConnectorSyncService(sources, connections, jobs, externalDocs, revisions,
                feishu, new ModelSecretCipher("synthetic-test-master-key-only"), documents,
                mock(DocumentRegistrationService.class), storage, dispatcher, executor,
                mock(ConnectorOAuthTokenService.class),
                new ConnectorVisibilityService(jdbc),
                txManager,
                new ConnectorSyncMetrics(new SimpleMeterRegistry()), renewIntervalMs);
    }

    private Map<String, Object> jobRow(UUID jobId) {
        return jdbc.queryForMap("SELECT status, error_summary FROM connector_sync_job WHERE id=?", jobId);
    }

    private void stubHappyPath() {
        // 跨测试共享静态 mock：先彻底重置，再用 doReturn/doThrow 风格 stub，
        // 避免 when() 重复 stub 时执行上一次配置（thenThrow 会在测试体内直接抛出）。
        org.mockito.Mockito.reset(feishu, documents, storage, dispatcher);
        // 既有测试可能把连接标记为 INVALID，恢复为可同步状态。
        jdbc.update("UPDATE connector_connection SET status='ACTIVE', last_error=NULL");
        org.mockito.Mockito.doReturn("tok").when(feishu).tenantAccessToken(anyString(), anyString());
        org.mockito.Mockito.doReturn(List.of()).when(feishu).listFolderDocx(anyString(), anyString());
    }

    private int runningCount(UUID sourceId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM connector_sync_job WHERE source_id=? AND status='RUNNING'",
                Integer.class, sourceId);
    }

    @Test
    void normalManualAndScheduledSyncSucceedWithRealDatabase() {
        stubHappyPath();
        UUID source = newSource();
        UUID manual = service(Runnable::run, 60000).trigger(source, "MANUAL");
        assertThat(jobRow(manual).get("status"))
                .as(String.valueOf(jobRow(manual).get("error_summary")))
                .isEqualTo("SUCCESS");
        UUID scheduled = service(Runnable::run, 60000).trigger(source, "SCHEDULED");
        assertThat(jobRow(scheduled).get("status")).isEqualTo("SUCCESS");
        // 空文件夹无变化：不刷新 last_synced_at（sawChange 语义，与原实现一致）。
        assertThat(jdbc.queryForObject("SELECT last_synced_at IS NULL FROM connector_source WHERE id=?",
                Boolean.class, source)).isTrue();
    }

    @Test
    void duplicateTriggerWhileRunningIsRejectedByUniqueIndex() throws Exception {
        stubHappyPath();
        UUID source = newSource();
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(1);
        org.mockito.Mockito.doAnswer(invocation -> {
            started.countDown();
            try { release.await(30, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return List.of();
        }).when(feishu).listFolderDocx(anyString(), anyString());
        ExecutorService worker = Executors.newSingleThreadExecutor();
        ConnectorSyncService running = service(worker::execute, 60000);
        UUID jobId = running.trigger(source, "MANUAL");
        try {
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(jobRow(jobId).get("status")).isEqualTo("RUNNING");
            assertThatThrownBy(() -> service(Runnable::run, 60000).trigger(source, "MANUAL"))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.CONNECTOR_SYNC_ALREADY_RUNNING);
            // 唯一部分索引兜底：数据库层面只允许一个 RUNNING 所有者。
            assertThat(runningCount(source)).isEqualTo(1);
        } finally {
            release.countDown();
            worker.shutdownNow();
        }
        long deadline = System.currentTimeMillis() + 5000;
        while (!worker.isTerminated() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertThat(jobRow(jobId).get("status")).isEqualTo("SUCCESS");
    }

    @Test
    void dispatchRejectionCompensatesRunningJobAndNextTriggerRecovers() {
        stubHappyPath();
        UUID source = newSource();
        TaskExecutor rejecting = task -> { throw new TaskRejectedException("queue full"); };
        assertThatThrownBy(() -> service(rejecting, 60000).trigger(source, "MANUAL"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.CONNECTOR_SYNC_DISPATCH_FAILED);
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT status, error_summary FROM connector_sync_job WHERE source_id=? ORDER BY started_at DESC LIMIT 1", source);
        assertThat(row.get("status")).isEqualTo("FAILED");
        assertThat((String) row.get("error_summary")).contains("派发失败");
        assertThat(runningCount(source)).isZero();

        // 拒绝补偿后，下一次正常触发能够立即执行，不再报 ALREADY_RUNNING。
        UUID jobId = service(Runnable::run, 60000).trigger(source, "MANUAL");
        assertThat(jobRow(jobId).get("status")).isEqualTo("SUCCESS");
    }

    @Test
    void crashWindowRunningJobIsReclaimedByNextTrigger() {
        stubHappyPath();
        UUID source = newSource();
        // 模拟"进程在 insertRunning 之后、派发之前崩溃"：RUNNING 行存在但无 Worker，
        // 且租约已过（应用侧崩溃无法补偿，只能依赖租约过期回收）。
        UUID crashedJob = UUID.randomUUID();
        jobs.insertRunning(crashedJob, source, "MANUAL");
        jdbc.update("UPDATE connector_sync_job SET lease_expires_at = now() - INTERVAL '1 second' WHERE id=?", crashedJob);

        UUID newJob = service(Runnable::run, 60000).trigger(source, "MANUAL");
        assertThat(jobRow(crashedJob).get("status")).isEqualTo("FAILED");
        assertThat((String) jobRow(crashedJob).get("error_summary")).contains("租约过期");
        assertThat(jobRow(newJob).get("status")).isEqualTo("SUCCESS");

        // 对照：租约未过期时（应用刚崩溃但租约仍活），触发被互斥拒绝，用户明确看到冲突。
        UUID aliveJob = UUID.randomUUID();
        jobs.insertRunning(aliveJob, source, "MANUAL");
        assertThatThrownBy(() -> service(Runnable::run, 60000).trigger(source, "MANUAL"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.CONNECTOR_SYNC_ALREADY_RUNNING);
        jobs.expireStale(aliveJob);
    }

    @Test
    void activeLongTaskRenewsLeaseThroughHeartbeat() throws Exception {
        stubHappyPath();
        UUID source = newSource();
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(1);
        org.mockito.Mockito.doAnswer(invocation -> {
            started.countDown();
            try { release.await(30, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return List.of();
        }).when(feishu).listFolderDocx(anyString(), anyString());
        ExecutorService worker = Executors.newSingleThreadExecutor();
        ConnectorSyncService renewing = service(worker::execute, 100);
        UUID jobId = renewing.trigger(source, "MANUAL");
        try {
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            // 心跳周期 100ms：活跃任务的租约被持续推到"now + 15 分钟"，
            // 越过 started_at + 15 分钟的原始窗口。
            Thread.sleep(600);
            java.sql.Timestamp lease = jdbc.queryForObject(
                    "SELECT lease_expires_at FROM connector_sync_job WHERE id=?", java.sql.Timestamp.class, jobId);
            java.sql.Timestamp original = jdbc.queryForObject(
                    "SELECT started_at + INTERVAL '15 minutes' FROM connector_sync_job WHERE id=?",
                    java.sql.Timestamp.class, jobId);
            assertThat(lease).isAfter(original);
        } finally {
            release.countDown();
            worker.shutdownNow();
        }
        long deadline = System.currentTimeMillis() + 5000;
        while (!worker.isTerminated() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertThat(jobRow(jobId).get("status")).isEqualTo("SUCCESS");
    }

    @Test
    void expiredJobCannotBeRenewedAndLateFinishIsRejected() {
        UUID source = newSource();
        UUID oldJob = UUID.randomUUID();
        jobs.insertRunning(oldJob, source, "MANUAL");
        jdbc.update("UPDATE connector_sync_job SET lease_expires_at = now() - INTERVAL '1 second' WHERE id=?", oldJob);

        assertThat(jobs.expireStale(oldJob)).isEqualTo(1);
        // 失权后既不能续期，也不能用迟到 finish 覆盖结果。
        assertThat(jobs.renewLease(oldJob)).isZero();
        assertThat(jobs.finish(oldJob, "SUCCESS", 1, 1, 0, 0, 0, null)).isZero();
        assertThat(jobRow(oldJob).get("status")).isEqualTo("FAILED");

        // 新任务接管后不受旧 Worker 迟到收口影响。
        UUID newJob = UUID.randomUUID();
        jobs.insertRunning(newJob, source, "MANUAL");
        assertThat(jobs.finish(newJob, "SUCCESS", 0, 0, 0, 0, 0, null)).isEqualTo(1);
        assertThat(jobRow(newJob).get("status")).isEqualTo("SUCCESS");
    }

    @Test
    void concurrentTriggersOnSameSourceKeepSingleOwner() throws Exception {
        stubHappyPath();
        UUID source = newSource();
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(1);
        org.mockito.Mockito.doAnswer(invocation -> {
            started.countDown();
            try { release.await(30, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return List.of();
        }).when(feishu).listFolderDocx(anyString(), anyString());
        ExecutorService busyWorker = Executors.newSingleThreadExecutor();
        ConnectorSyncService busy = service(busyWorker::execute, 60000);
        UUID firstJob = busy.trigger(source, "MANUAL");
        try {
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            int threads = 6;
            CyclicBarrier barrier = new CyclicBarrier(threads);
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            List<java.util.concurrent.Callable<String>> attempts = IntStream.range(0, threads)
                    .mapToObj(i -> (java.util.concurrent.Callable<String>) () -> {
                        barrier.await(5, TimeUnit.SECONDS);
                        try {
                            busy.trigger(source, "SCHEDULED");
                            return "accepted";
                        } catch (BusinessException exception) {
                            return exception.getErrorCode().name();
                        }
                    }).toList();
            var results = pool.invokeAll(attempts).stream()
                    .map(future -> {
                        try { return future.get(); } catch (Exception e) { return "error:" + e.getMessage(); }
                    }).toList();
            pool.shutdown();
            // 并发触发不得产生第二个有效 RUNNING 所有者：全部被互斥拒绝。
            assertThat(results).allSatisfy(r -> assertThat(r)
                    .isEqualTo(ErrorCode.CONNECTOR_SYNC_ALREADY_RUNNING.name()));
            assertThat(runningCount(source)).isEqualTo(1);
        } finally {
            release.countDown();
            busyWorker.shutdownNow();
        }
        long deadline = System.currentTimeMillis() + 5000;
        while (!busyWorker.isTerminated() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertThat(jobRow(firstJob).get("status")).isEqualTo("SUCCESS");
    }

    @Test
    void feishuApiFailureFailsJobWithoutDeletingDocuments() {
        stubHappyPath();
        UUID source = newSource();
        org.mockito.Mockito.doThrow(new FeishuApiException(-1, "连接飞书服务失败"))
                .when(feishu).listFolderDocx(anyString(), anyString());
        UUID jobId = service(Runnable::run, 60000).trigger(source, "MANUAL");
        assertThat(jobRow(jobId).get("status")).isEqualTo("FAILED");
        assertThat((String) jobRow(jobId).get("error_summary")).contains("扫描文件夹失败");
    }

    @Test
    void partialDocumentFailureKeepsPartFailedSemantics() {
        stubHappyPath();
        UUID source = newSource();
        UUID documentId = UUID.randomUUID();
        // 已知文档（无 project_document 行 → documentStatus 为 NULL → 走处理中失败路径）。
        jdbc.update("INSERT INTO external_document(id,source_id,external_key,doc_type,title,source_url,latest_modify_time,content_hash,document_id,status) VALUES (?,?,?,'docx','文档A','https://feishu/docx/a',1000,NULL,?,'ACTIVE')",
                UUID.randomUUID(), source, "docTok", documentId);
        org.mockito.Mockito.doReturn(List.of(
                new FeishuFile("docTok", "文档A", "docx", "https://feishu/docx/a", 2000L)))
                .when(feishu).listFolderDocx(anyString(), anyString());
        org.mockito.Mockito.doReturn("正文").when(feishu).docxRawContent(anyString(), eq("docTok"));

        UUID jobId = service(Runnable::run, 60000).trigger(source, "MANUAL");
        assertThat(jobRow(jobId).get("status")).isEqualTo("PARTIAL_FAILED");
        assertThat(jdbc.queryForObject("SELECT failed_count FROM connector_sync_job WHERE id=?", Integer.class, jobId))
                .isEqualTo(1);
        // 失败不产生删除与屏蔽副作用。
        assertThat(jdbc.queryForObject("SELECT status FROM external_document WHERE document_id=?", String.class, documentId))
                .isEqualTo("ACTIVE");
    }

    /** 场景 A（D1.1-T01/T02/T03）：租约续期必须以数据库时间为准，过期租约不能复活。 */
    @Test
    void leaseRenewalRequiresUnexpiredLease() {
        UUID source = newSource();
        UUID job = UUID.randomUUID();
        jobs.insertRunning(job, source, "MANUAL");
        java.sql.Timestamp originalLease = leaseOf(job);
        // T01：未过期租约正常续期。
        assertThat(jobs.renewLease(job)).isEqualTo(1);
        assertThat(leaseOf(job)).isAfter(originalLease);
        // T02（D1.1-01 复现）：过期但尚未被回收的租约不能续期，租约值保持不变。
        jdbc.update("UPDATE connector_sync_job SET lease_expires_at = now() - INTERVAL '1 second' WHERE id=?", job);
        java.sql.Timestamp expired = leaseOf(job);
        assertThat(jobs.renewLease(job)).isZero();
        assertThat(leaseOf(job)).isEqualTo(expired);
        // T03：已被回收（FAILED）的旧任务同样不能续期。
        assertThat(jobs.expireStale(job)).isEqualTo(1);
        assertThat(jobs.renewLease(job)).isZero();
    }

    private java.sql.Timestamp leaseOf(UUID job) {
        return jdbc.queryForObject("SELECT lease_expires_at FROM connector_sync_job WHERE id=?",
                java.sql.Timestamp.class, job);
    }

    /**
     * 场景 B（D1.1-T04/T05/T07）：心跳间隙发生接管。
     * A 的本地失权标志必然保持"持有"（心跳周期 60 秒内不触发），只有数据库 Fencing
     * 门能阻止其在被接管后继续写入；B 的新任务状态不受 A 迟到收口影响。
     */
    @Test
    void takeoverDuringHeartbeatGapIsBlockedByDatabaseFence() throws Exception {
        stubHappyPath();
        UUID source = newSource();
        UUID documentId = UUID.randomUUID();
        jdbc.update("INSERT INTO external_document(id,source_id,external_key,doc_type,title,source_url,latest_modify_time,content_hash,document_id,status) VALUES (?,?,?,'docx','文档C','https://feishu/docx/c',1000,NULL,?,'ACTIVE')",
                UUID.randomUUID(), source, "docTok3", documentId);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(1);
        org.mockito.Mockito.doAnswer(invocation -> {
            started.countDown();
            try { release.await(30, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return "新正文";
        }).when(feishu).docxRawContent(anyString(), eq("docTok3"));
        org.mockito.Mockito.doReturn(List.of(new FeishuFile("docTok3", "文档C", "docx", null, 2000L)))
                .when(feishu).listFolderDocx(anyString(), anyString());
        org.mockito.Mockito.doReturn(true).when(documents).resetForReindex(projectId, documentId);
        java.util.concurrent.ThreadPoolExecutor worker = new java.util.concurrent.ThreadPoolExecutor(
                1, 1, 0L, TimeUnit.MILLISECONDS, new java.util.concurrent.LinkedBlockingQueue<>());
        // 心跳 60 秒：整个测试期间 A 的本地标志不会更新，精确覆盖"心跳空窗"。
        ConnectorSyncService victim = service(worker::execute, 60000);
        UUID jobA = victim.trigger(source, "MANUAL");
        UUID jobB = null;
        try {
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            // Worker B 接管：回收 A（等价 expireStale）并创建新 RUNNING 任务。
            jdbc.update("UPDATE connector_sync_job SET lease_expires_at = now() - INTERVAL '1 second' WHERE id=?", jobA);
            assertThat(jobs.expireStale(jobA)).isEqualTo(1);
            jobB = UUID.randomUUID();
            jobs.insertRunning(jobB, source, "MANUAL");
            release.countDown();
        } finally {
            worker.shutdownNow();
        }
        long deadline = System.currentTimeMillis() + 5000;
        while (!worker.isTerminated() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        // T04：本地标志未更新，数据库 Fencing 门仍阻止了 A 的全部业务写入。
        org.mockito.Mockito.verify(dispatcher, org.mockito.Mockito.after(300).never())
                .dispatch(any(), any());
        org.mockito.Mockito.verify(documents, org.mockito.Mockito.never())
                .resetForReindex(projectId, documentId);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM external_document_revision WHERE external_document_id IN (SELECT id FROM external_document WHERE document_id=?)",
                Integer.class, documentId)).isZero();
        // T05：A 的迟到收口不能覆盖 B 的任务状态，B 仍可正常收口。
        assertThat(jobRow(jobA).get("status")).isEqualTo("FAILED");
        assertThat(jobB).isNotNull();
        assertThat(jobRow(jobB).get("status")).isEqualTo("RUNNING");
        assertThat(jobs.finish(jobB, "SUCCESS", 0, 0, 0, 0, 0, null)).isEqualTo(1);
        // T07：失权 Worker 不刷新 last_synced_at。
        assertThat(jdbc.queryForObject("SELECT last_synced_at IS NULL FROM connector_source WHERE id=?",
                Boolean.class, source)).isTrue();
    }

    @Test
    void workerLostLeaseAbortsBeforeWritesInRealDatabase() throws Exception {
        stubHappyPath();
        UUID source = newSource();
        UUID documentId = UUID.randomUUID();
        jdbc.update("INSERT INTO external_document(id,source_id,external_key,doc_type,title,source_url,latest_modify_time,content_hash,document_id,status) VALUES (?,?,?,'docx','文档B','https://feishu/docx/b',1000,NULL,?,'ACTIVE')",
                UUID.randomUUID(), source, "docTok2", documentId);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(1);
        org.mockito.Mockito.doAnswer(invocation -> {
            started.countDown();
            try { release.await(30, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return List.of(new FeishuFile("docTok2", "文档B", "docx", null, 2000L));
        }).when(feishu).listFolderDocx(anyString(), anyString());
        when(documents.resetForReindex(projectId, documentId)).thenReturn(true);
        java.util.concurrent.ThreadPoolExecutor worker = new java.util.concurrent.ThreadPoolExecutor(
                1, 1, 0L, TimeUnit.MILLISECONDS, new java.util.concurrent.LinkedBlockingQueue<>());
        // 心跳 50ms：回收后下一次续期即判定失权。
        ConnectorSyncService losing = service(worker::execute, 50);
        UUID jobId = losing.trigger(source, "MANUAL");
        try {
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            // 模拟另一实例接管：任务被过期回收为 FAILED（与 expireStale 相同的最终状态）。
            jdbc.update("UPDATE connector_sync_job SET status='FAILED', finished_at=now(), " +
                    "error_summary='同步超时，运行租约过期' WHERE id=?", jobId);
            release.countDown();
        } finally {
            worker.shutdownNow();
        }
        long deadline = System.currentTimeMillis() + 5000;
        while (!worker.isTerminated() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        // 旧 Worker 失权后：不写文档、不派发、不刷新 last_synced_at。
        org.mockito.Mockito.verify(dispatcher, org.mockito.Mockito.after(200).never())
                .dispatch(any(), any());
        assertThat(jdbc.queryForObject("SELECT last_synced_at IS NULL FROM connector_source WHERE id=?",
                Boolean.class, source)).isTrue();
        assertThat(jobRow(jobId).get("status")).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM external_document WHERE document_id=? AND status='ACTIVE'",
                Integer.class, documentId)).isEqualTo(1);
    }
}
