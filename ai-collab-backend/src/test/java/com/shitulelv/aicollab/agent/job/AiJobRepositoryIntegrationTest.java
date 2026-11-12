package com.shitulelv.aicollab.agent.job;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V91 + AiJobRepository/AiJobService 真库集成测试（Testcontainers）：
 * 迁移可启动、租约/心跳 SQL、结果幂等与 review_status 固定 PENDING_REVIEW、
 * 快照只读过滤（ACTIVE + 未被 SUPERSEDE），以及“没有 agent_memory 写入路径”。
 */
@Testcontainers(disabledWithoutDocker = true)
class AiJobRepositoryIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("pgvector/pgvector:pg17");

    private static JdbcTemplate jdbc;
    private static AiJobService service;

    @BeforeAll
    static void migrate() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        service = new AiJobService(new AiJobRepository(jdbc),
                new AiJobProperties(true, 3, 120, false), new SimpleMeterRegistry(),
                new ObjectMapper(), Clock.systemUTC());
    }

    @Test
    void jobLifecyclePersistsLeaseHeartbeatResultsAndReviewStatus() {
        UUID user = user("job-lifecycle");
        UUID project = project(user, "Job 生命周期");
        AiJobRecord job = service.create(project, "{}", user);
        assertThat(job.status()).isEqualTo(AiJobStatus.QUEUED);
        assertThat(job.attemptCount()).isZero();
        assertThat(job.leaseEpoch()).isZero();
        assertThat(job.maxAttempts()).isEqualTo(3);

        AiJobService.Lease lease = service.lease(job.id());
        assertThat(lease.attempt()).isEqualTo(1);
        assertThat(lease.leaseEpoch()).isEqualTo(1);
        assertThat(lease.leaseToken()).isNotNull();
        assertThat(jdbc.queryForObject("SELECT status FROM ai_job WHERE id=?",
                String.class, job.id())).isEqualTo("LEASED");
        assertThat(jdbc.queryForObject("SELECT attempt_count FROM ai_job WHERE id=?",
                Integer.class, job.id())).isOne();
        assertThat(jdbc.queryForObject("SELECT lease_epoch FROM ai_job WHERE id=?",
                Long.class, job.id())).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_job_attempt WHERE job_id=?",
                Integer.class, job.id())).isOne();

        AiJobService.Heartbeat heartbeat = service.heartbeat(job.id(), 1, 1);
        assertThat(heartbeat.status()).isEqualTo(AiJobStatus.RUNNING);
        assertThat(jdbc.queryForObject(
                "SELECT heartbeat_at IS NOT NULL FROM ai_job_attempt WHERE job_id=? AND attempt_no=1",
                Boolean.class, job.id())).isTrue();

        UUID active = memory(project, user, "ACTIVE", "活跃记忆");
        UUID disabled = memory(project, user, "DISABLED", "已停用记忆");
        UUID superseded = memory(project, user, "ACTIVE", "被替代记忆");
        jdbc.update("UPDATE agent_memory SET superseded_by=? WHERE id=?", active, superseded);
        int memoriesBefore = jdbc.queryForObject("SELECT count(*) FROM agent_memory",
                Integer.class);

        AiJobService.Snapshot snapshot = service.snapshot(job.id(), 1, 1);
        assertThat(snapshot.snapshotHash()).matches("sha256:[0-9a-f]{32}");
        assertThat(snapshot.facts()).anySatisfy(fact -> {
            assertThat(fact.ref()).isEqualTo("fact:project");
            assertThat(fact.text()).contains("Job 生命周期");
        });
        assertThat(snapshot.existingMemories()).extracting(AiJobService.ExistingMemory::id)
                .containsExactly(active);
        assertThat(snapshot.existingMemories()).extracting(AiJobService.ExistingMemory::id)
                .doesNotContain(disabled, superseded);
        assertThat(jdbc.queryForObject("""
                SELECT snapshot_hash FROM ai_job_attempt WHERE job_id=? AND attempt_no=1
                """, String.class, job.id())).isEqualTo(snapshot.snapshotHash());
        assertThat(jdbc.queryForObject("""
                SELECT jsonb_array_length(snapshot_refs)
                  FROM ai_job_attempt WHERE job_id=? AND attempt_no=1
                """, Integer.class, job.id())).isGreaterThanOrEqualTo(2);

        AiJobCandidate candidate = new AiJobCandidate("DECISION", "发布窗口", "周五发布",
                new BigDecimal("0.8"), "fact:project", "a".repeat(64));
        AiJobService.ResultsOutcome first = service.submitResults(job.id(), 1, 1,
                List.of(candidate));
        assertThat(first.accepted()).isOne();
        assertThat(first.duplicates()).isZero();
        assertThat(first.status()).isEqualTo(AiJobStatus.SUCCEEDED);
        assertThat(jdbc.queryForObject("SELECT status FROM ai_job WHERE id=?",
                String.class, job.id())).isEqualTo("SUCCEEDED");
        assertThat(jdbc.queryForObject("SELECT status FROM ai_job_attempt WHERE job_id=? AND attempt_no=1",
                String.class, job.id())).isEqualTo("SUCCEEDED");
        assertThat(jdbc.queryForObject("SELECT review_status FROM ai_job_result WHERE job_id=?",
                String.class, job.id())).isEqualTo("PENDING_REVIEW");
        assertThat(jdbc.queryForObject("""
                SELECT snapshot_hash FROM ai_job_result WHERE job_id=?
                """, String.class, job.id())).isEqualTo(snapshot.snapshotHash());
        assertThat(jdbc.queryForObject("""
                SELECT jsonb_array_length(snapshot_refs) FROM ai_job_result WHERE job_id=?
                """, Integer.class, job.id())).isGreaterThanOrEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM agent_memory", Integer.class))
                .isEqualTo(memoriesBefore);

        AiJobService.ResultsOutcome duplicate = service.submitResults(job.id(), 1, 1,
                List.of(candidate));
        assertThat(duplicate.accepted()).isZero();
        assertThat(duplicate.duplicates()).isOne();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_job_result WHERE job_id=?",
                Integer.class, job.id())).isOne();
        assertThat(jdbc.queryForObject("SELECT review_status FROM ai_job_result WHERE job_id=?",
                String.class, job.id())).isEqualTo("PENDING_REVIEW");
    }

    @Test
    void failedAttemptWithRetryableBecomesRetryableAndRequeuesWhenDue() {
        UUID user = user("job-retry");
        UUID project = project(user, "Job 重试");
        AiJobRecord job = service.create(project, "{}", user);
        service.lease(job.id());

        AiJobRecord failed = service.fail(job.id(), 1, 1, true, "TIMEOUT");
        assertThat(failed.status()).isEqualTo(AiJobStatus.FAILED_RETRYABLE);
        assertThat(failed.nextAttemptAt()).isNotNull();
        assertThat(failed.nextAttemptAt()).isAfter(OffsetDateTime.now().minusSeconds(1));

        AiJobService.RecoverySummary summary =
                service.recoverExpired(OffsetDateTime.now().plusSeconds(600), 100);
        assertThat(summary.requeued()).isGreaterThanOrEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM ai_job WHERE id=?",
                String.class, job.id())).isEqualTo("QUEUED");
    }

    @Test
    void snapshotStaysFrozenAfterProjectAndMemoryChanges() {
        UUID user = user("job-frozen-snapshot");
        UUID project = project(user, "冻结快照");
        AiJobRecord job = service.create(project, "{}", user);
        AiJobService.Lease lease = service.lease(job.id());
        AiJobService.Snapshot first = service.snapshot(job.id(), lease.attempt(), lease.leaseEpoch());

        jdbc.update("UPDATE project SET name='改名后的项目' WHERE id=?", project);
        memory(project, user, "ACTIVE", "快照之后新增");
        AiJobService.Snapshot second = service.snapshot(
                job.id(), lease.attempt(), lease.leaseEpoch());

        assertThat(second.snapshotHash()).isEqualTo(first.snapshotHash());
        assertThat(second.facts()).isEqualTo(first.facts());
        assertThat(second.existingMemories()).isEqualTo(first.existingMemories());
    }

    @Test
    void resultRejectsPrefixOnlyAndRefsOutsideFrozenSnapshotWithoutWritingRows() {
        UUID user = user("job-source-ref");
        UUID project = project(user, "来源引用");
        AiJobRecord job = service.create(project, "{}", user);
        AiJobService.Lease lease = service.lease(job.id());
        service.snapshot(job.id(), lease.attempt(), lease.leaseEpoch());
        UUID laterMemory = memory(project, user, "ACTIVE", "快照后记忆");

        assertThatThrownBy(() -> service.submitResults(job.id(), lease.attempt(),
                lease.leaseEpoch(), List.of(candidate("fact:not-here"))))
                .isInstanceOfSatisfying(AiJobException.class,
                        failure -> assertThat(failure.kind())
                                .isEqualTo(AiJobException.Kind.INVALID_REQUEST));
        assertThatThrownBy(() -> service.submitResults(job.id(), lease.attempt(),
                lease.leaseEpoch(), List.of(candidate("memory:" + laterMemory))))
                .isInstanceOfSatisfying(AiJobException.class,
                        failure -> assertThat(failure.kind())
                                .isEqualTo(AiJobException.Kind.INVALID_REQUEST));
        assertThatThrownBy(() -> service.submitResults(job.id(), lease.attempt(),
                lease.leaseEpoch(), List.of(candidate("document:" + UUID.randomUUID()))))
                .isInstanceOfSatisfying(AiJobException.class,
                        failure -> assertThat(failure.kind())
                                .isEqualTo(AiJobException.Kind.INVALID_REQUEST));
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM ai_job_result WHERE job_id=?",
                Integer.class, job.id())).isZero();
    }

    // ------------------------------------------------------------------
    // fixtures
    // ------------------------------------------------------------------

    private static UUID user(String prefix) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO app_user(id,username,password_hash,display_name) VALUES (?,?,?,?)",
                id, prefix + "-" + id.toString().substring(0, 8), "test-only-hash", "W5 Job");
        return id;
    }

    private static UUID project(UUID owner, String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO project(id,name,description,owner_id,created_by,start_date,due_date)
                VALUES (?,?,?,?,?,DATE '2026-11-01',DATE '2026-12-31')
                """, id, name, "项目描述", owner, owner);
        return id;
    }

    private static UUID memory(UUID project, UUID user, String status, String title) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO agent_memory(id,project_id,type,title,content,source_type,status,
                                         created_by,updated_by)
                VALUES (?,?,?,?,?,?,?,?,?)
                """, id, project, "DECISION", title, "内容-" + title, "TEST", status, user, user);
        return id;
    }

    private static AiJobCandidate candidate(String sourceRef) {
        return new AiJobCandidate("DECISION", "候选", "内容", new BigDecimal("0.8"),
                sourceRef, UUID.randomUUID().toString().replace("-", "")
                + UUID.randomUUID().toString().replace("-", ""));
    }
}
