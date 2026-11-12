package com.shitulelv.aicollab.agent.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.agent.application.view.AgentMemoryCandidateView;
import com.shitulelv.aicollab.agent.application.view.AgentMemoryView;
import com.shitulelv.aicollab.agent.infrastructure.repository.AgentMemoryRepository;
import com.shitulelv.aicollab.agent.job.AiJobCandidate;
import com.shitulelv.aicollab.agent.job.AiJobException;
import com.shitulelv.aicollab.agent.job.AiJobProperties;
import com.shitulelv.aicollab.agent.job.AiJobRecord;
import com.shitulelv.aicollab.agent.job.AiJobRepository;
import com.shitulelv.aicollab.agent.job.AiJobService;
import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.project.application.service.AuditService;
import com.shitulelv.aicollab.project.domain.policy.ProjectAccessGuard;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

@Testcontainers(disabledWithoutDocker = true)
class AgentMemoryCandidateServiceTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("pgvector/pgvector:pg17");

    private static JdbcTemplate jdbc;
    private static AiJobService jobs;
    private static AgentMemoryCandidateService candidates;
    private static UUID user;
    private static UUID project;

    @BeforeAll
    static void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        user = user("candidate");
        project = project(user, "候选审核");
        ObjectMapper json = new ObjectMapper();
        AiJobRepository jobRepository = new AiJobRepository(jdbc);
        jobs = new AiJobService(jobRepository, new AiJobProperties(true, 3, 120, false),
                new SimpleMeterRegistry(), json);
        ProjectAccessGuard access = mock(ProjectAccessGuard.class);
        AgentMemoryService memories = new AgentMemoryService(new AgentMemoryRepository(jdbc),
                access, mock(AgentMemorySourceVerifier.class), mock(AuditService.class));
        candidates = new AgentMemoryCandidateService(jobRepository, memories, access,
                mock(AuditService.class), json);
    }

    @BeforeEach
    void cleanProjectData() {
        jdbc.update("DELETE FROM ai_job WHERE project_id=?", project);
        jdbc.update("DELETE FROM agent_memory WHERE project_id=?", project);
    }

    @Test
    void approvePublishesOnceAndIsIdempotent() {
        AiJobResult jobResult = result("发布候选");
        List<AgentMemoryCandidateView> pending = candidates.list(project, user, "PENDING_REVIEW");
        AgentMemoryCandidateView candidate = pending.stream()
                .filter(view -> view.id().equals(jobResult.resultId())).findFirst().orElseThrow();
        assertThat(candidate.title()).isEqualTo("发布候选");

        AgentMemoryView first = candidates.approve(project, candidate.id(), user, "通过");
        AgentMemoryView second = candidates.approve(project, candidate.id(), user, null);

        assertThat(second.id()).isEqualTo(first.id());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM agent_memory WHERE project_id=?",
                Integer.class, project)).isOne();
        assertThat(jdbc.queryForObject("SELECT review_status FROM ai_job_result WHERE id=?",
                String.class, jobResult.resultId())).isEqualTo("ACCEPTED");
        assertThat(jdbc.queryForObject("SELECT source_type FROM agent_memory WHERE id=?",
                String.class, first.id())).isEqualTo("AI_JOB_RESULT");
        assertThat(jdbc.queryForObject("SELECT source_id FROM agent_memory WHERE id=?",
                UUID.class, first.id())).isEqualTo(jobResult.resultId());
    }

    @Test
    void rejectDoesNotPublishAndRequiresReason() {
        AiJobResult jobResult = result("拒绝候选");
        AgentMemoryCandidateView candidate = candidates.list(project, user, "PENDING_REVIEW")
                .stream().filter(view -> view.id().equals(jobResult.resultId())).findFirst()
                .orElseThrow();

        assertThatThrownBy(() -> candidates.reject(project, candidate.id(), user, " ")).isInstanceOf(
                BusinessException.class);
        AgentMemoryCandidateView rejected = candidates.reject(project, candidate.id(), user,
                "来源不足");

        assertThat(rejected.reviewStatus()).isEqualTo("REJECTED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM agent_memory WHERE project_id=?",
                Integer.class, project)).isZero();
        assertThatThrownBy(() -> candidates.approve(project, candidate.id(), user, null))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void candidateFromAnotherProjectIsNotFound() {
        AiJobResult jobResult = result("跨项目候选");
        UUID anotherProject = project(user, "另一个项目");

        assertThatThrownBy(() -> candidates.approve(anotherProject, jobResult.resultId(), user, null))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getErrorCode())
                        .isEqualTo(com.shitulelv.aicollab.common.exception.ErrorCode
                                .AGENT_MEMORY_CANDIDATE_NOT_FOUND));
    }

    @Test
    void candidateWithMemoryRefFromFrozenSnapshotIsPublished() {
        UUID memory = memory(project, user, "ACTIVE", "已确认的发布窗口");
        AiJobResult jobResult = result("记忆引用候选", "memory:" + memory);

        AgentMemoryView published = candidates.approve(
                project, jobResult.resultId(), user, "来源命中快照");

        assertThat(published.projectId()).isEqualTo(project);
        assertThat(jdbc.queryForObject(
                "SELECT review_status FROM ai_job_result WHERE id=?",
                String.class, jobResult.resultId())).isEqualTo("ACCEPTED");
    }

    @Test
    void submissionRejectsPrefixOnlyAndUnknownRefsBeforeAnyResultIsWritten() {
        AiJobRecord job = jobs.create(project, "{}", user);
        AiJobService.Lease lease = jobs.lease(job.id());
        jobs.snapshot(job.id(), lease.attempt(), lease.leaseEpoch());

        assertInvalidSubmission(job, lease, "fact:not-in-snapshot");
        assertInvalidSubmission(job, lease, "doc:42");
        assertInvalidSubmission(job, lease, "memory:" + UUID.randomUUID());

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM ai_job_result WHERE job_id=?",
                Integer.class, job.id())).isZero();
    }

    @Test
    void submissionRejectsRefThatAppearedAfterAttemptSnapshotWasFrozen() {
        AiJobRecord job = jobs.create(project, "{}", user);
        AiJobService.Lease lease = jobs.lease(job.id());
        jobs.snapshot(job.id(), lease.attempt(), lease.leaseEpoch());
        UUID laterMemory = memory(project, user, "ACTIVE", "快照之后新增的记忆");

        assertInvalidSubmission(job, lease, "memory:" + laterMemory);
    }

    @Test
    void submissionRejectsRefFromAnotherJobsSnapshot() {
        AiJobRecord first = jobs.create(project, "{}", user);
        AiJobService.Lease firstLease = jobs.lease(first.id());
        jobs.snapshot(first.id(), firstLease.attempt(), firstLease.leaseEpoch());

        UUID memoryOnlyInSecondJob = memory(project, user, "ACTIVE", "第二 Job 才可见");
        AiJobRecord second = jobs.create(project, "{}", user);
        AiJobService.Lease secondLease = jobs.lease(second.id());
        AiJobService.Snapshot secondSnapshot = jobs.snapshot(
                second.id(), secondLease.attempt(), secondLease.leaseEpoch());
        assertThat(secondSnapshot.existingMemories())
                .extracting(AiJobService.ExistingMemory::id)
                .contains(memoryOnlyInSecondJob);

        assertInvalidSubmission(first, firstLease, "memory:" + memoryOnlyInSecondJob);
    }

    @Test
    void submissionRejectsAttemptSnapshotTamperedAfterFreeze() {
        AiJobRecord job = jobs.create(project, "{}", user);
        AiJobService.Lease lease = jobs.lease(job.id());
        jobs.snapshot(job.id(), lease.attempt(), lease.leaseEpoch());
        jdbc.update("""
                UPDATE ai_job_attempt SET snapshot_refs='[]'::jsonb
                 WHERE job_id=? AND attempt_no=?
                """, job.id(), lease.attempt());

        AiJobCandidate candidate = new AiJobCandidate("DECISION", "快照被篡改",
                "不应落库", new BigDecimal("0.9"), "fact:project", "c".repeat(64));
        assertThatThrownBy(() -> jobs.submitResults(
                job.id(), lease.attempt(), lease.leaseEpoch(), List.of(candidate)))
                .isInstanceOfSatisfying(AiJobException.class,
                        error -> assertThat(error.kind())
                                .isEqualTo(AiJobException.Kind.INVALID_STATE));
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM ai_job_result WHERE job_id=?",
                Integer.class, job.id())).isZero();
    }

    @Test
    void submissionRejectsRefFromAnotherProject() {
        AiJobRecord job = jobs.create(project, "{}", user);
        AiJobService.Lease lease = jobs.lease(job.id());
        jobs.snapshot(job.id(), lease.attempt(), lease.leaseEpoch());
        UUID otherProject = project(user, "跨项目来源");
        UUID otherMemory = memory(otherProject, user, "ACTIVE", "别的项目记忆");

        assertInvalidSubmission(job, lease, "memory:" + otherMemory);
    }

    @Test
    void invalidPrefixAndUnknownRefAreRejectedAtApprovalWithFixedCode() {
        AiJobResult invalidPrefix = result("审批期非法前缀", "fact:project");
        jdbc.update("""
                UPDATE ai_job_result
                   SET payload=jsonb_set(payload, '{sourceRef}', '"doc:42"'::jsonb)
                 WHERE id=?
                """, invalidPrefix.resultId());
        assertApprovalRejected(invalidPrefix,
                ErrorCode.AGENT_MEMORY_CANDIDATE_SOURCE_REF_INVALID);

        AiJobResult unknown = result("审批期不存在引用", "fact:project");
        jdbc.update("""
                UPDATE ai_job_result
                   SET payload=jsonb_set(payload, '{sourceRef}', '"fact:ghost"'::jsonb)
                 WHERE id=?
                """, unknown.resultId());
        assertApprovalRejected(unknown,
                ErrorCode.AGENT_MEMORY_CANDIDATE_SOURCE_REF_INVALID);
    }

    @Test
    void approvalRejectsWhenFrozenSnapshotHashChanges() {
        AiJobResult jobResult = result("快照被改写");
        jdbc.update("""
                UPDATE ai_job_attempt
                   SET snapshot_hash='sha256:cccccccccccccccccccccccccccccccc'
                 WHERE job_id=? AND attempt_no=?
                """, jobResult.jobId(), jobResult.attempt());

        assertApprovalRejected(jobResult,
                ErrorCode.AGENT_MEMORY_CANDIDATE_SNAPSHOT_CHANGED);
    }

    private static AiJobResult result(String title) {
        return result(title, "fact:project");
    }

    private static AiJobResult result(String title, String sourceRef) {
        AiJobRecord job = jobs.create(project, "{}", user);
        AiJobService.Lease lease = jobs.lease(job.id());
        jobs.snapshot(job.id(), lease.attempt(), lease.leaseEpoch());
        AiJobCandidate candidate = new AiJobCandidate("DECISION", title, title + "内容",
                new BigDecimal("0.9"), sourceRef, UUID.randomUUID().toString().replace("-", "")
                        + UUID.randomUUID().toString().replace("-", ""));
        jobs.submitResults(job.id(), lease.attempt(), lease.leaseEpoch(), List.of(candidate));
        UUID resultId = jdbc.queryForObject("SELECT id FROM ai_job_result WHERE job_id=?",
                UUID.class, job.id());
        return new AiJobResult(job.id(), resultId, lease.attempt(), lease.leaseEpoch());
    }

    private static void assertInvalidSubmission(AiJobRecord job, AiJobService.Lease lease,
            String sourceRef) {
        AiJobCandidate candidate = new AiJobCandidate("DECISION", "非法来源",
                "不应落库", new BigDecimal("0.9"), sourceRef, "b".repeat(64));
        assertThatThrownBy(() -> jobs.submitResults(
                job.id(), lease.attempt(), lease.leaseEpoch(), List.of(candidate)))
                .isInstanceOfSatisfying(AiJobException.class,
                        error -> assertThat(error.kind())
                                .isEqualTo(AiJobException.Kind.INVALID_REQUEST));
    }

    private void assertApprovalRejected(AiJobResult jobResult, ErrorCode expected) {
        assertThatThrownBy(() -> candidates.approve(
                project, jobResult.resultId(), user, null))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(expected));
        assertThat(jdbc.queryForObject(
                "SELECT review_status FROM ai_job_result WHERE id=?",
                String.class, jobResult.resultId())).isEqualTo("PENDING_REVIEW");
    }

    private static UUID memory(UUID projectId, UUID userId, String status, String title) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO agent_memory(id,project_id,type,title,content,source_type,status,
                                         created_by,updated_by)
                VALUES (?,?,?,?,?,?,?,?,?)
                """, id, projectId, "DECISION", title, "内容-" + title, "TEST", status,
                userId, userId);
        return id;
    }

    private static UUID user(String prefix) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO app_user(id,username,password_hash,display_name) VALUES (?,?,?,?)",
                id, prefix + "-" + id.toString().substring(0, 8), "test-only-hash", "W5 审核");
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

    private record AiJobResult(UUID jobId, UUID resultId, int attempt, long leaseEpoch) {
    }
}
