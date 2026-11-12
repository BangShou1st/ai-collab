package com.shitulelv.aicollab.agent.job;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AiJobService 单元测试（mock repository）：租约/心跳 epoch 冲突、CAS 重试、重试次数与退避、
 * 过期回收、结果幂等与失败分类；同时断言指标口径。
 */
class AiJobServiceTest {

    private static final UUID JOB_ID = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd");
    private static final UUID PROJECT_ID = UUID.fromString("eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee");
    private static final UUID CREATED_BY = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff");
    private static final String DEDUPE = "a".repeat(64);
    private static final Instant NOW_INSTANT = Instant.parse("2026-11-12T04:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW_INSTANT, ZoneOffset.UTC);
    private static final OffsetDateTime NOW = OffsetDateTime.now(CLOCK);

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private AiJobRepository repository;
    private AiJobService service;

    @BeforeEach
    void setUp() {
        repository = mock(AiJobRepository.class);
        service = new AiJobService(repository,
                new AiJobProperties(true, 3, 120, false), registry, new com.fasterxml.jackson.databind.ObjectMapper(),
                CLOCK);
        when(repository.findAttemptSnapshot(JOB_ID, 1, 1L))
                .thenReturn(Optional.of(attemptSnapshot()));
    }

    // ------------------------------------------------------------------
    // 租约
    // ------------------------------------------------------------------

    @Test
    void leaseIncrementsAttemptAndEpochAndRecordsTransition() {
        AiJobRecord queued = job(AiJobStatus.QUEUED, 0, 3, 0, 0, null);
        AiJobRecord leased = job(AiJobStatus.LEASED, 1, 3, 1, 1, NOW.plusSeconds(120));
        when(repository.require(JOB_ID)).thenReturn(queued);
        when(repository.casLease(eq(queued), any(UUID.class), any(), any()))
                .thenReturn(Optional.of(leased));

        AiJobService.Lease lease = service.lease(JOB_ID);

        assertThat(lease.attempt()).isEqualTo(1);
        assertThat(lease.leaseEpoch()).isEqualTo(1);
        assertThat(lease.job().status()).isEqualTo(AiJobStatus.LEASED);
        verify(repository).insertAttempt(JOB_ID, 1, 1, NOW);
        assertThat(counter("ai_job.transitions", "from", "queued", "to", "leased")).isEqualTo(1.0);
    }

    @Test
    void leaseRejectsNonQueuedJob() {
        when(repository.require(JOB_ID)).thenReturn(job(AiJobStatus.RUNNING, 1, 3, 1, 2, NOW.plusSeconds(60)));

        assertThatThrownBy(() -> service.lease(JOB_ID))
                .isInstanceOfSatisfying(AiJobException.class,
                        failure -> assertThat(failure.kind()).isEqualTo(AiJobException.Kind.INVALID_STATE));
        verify(repository, never()).casLease(any(), any(), any(), any());
    }

    // ------------------------------------------------------------------
    // 心跳
    // ------------------------------------------------------------------

    @Test
    void heartbeatRejectsMismatchedEpochWithoutTouchingRepository() {
        when(repository.require(JOB_ID)).thenReturn(job(AiJobStatus.RUNNING, 1, 3, 2, 2, NOW.plusSeconds(60)));

        assertThatThrownBy(() -> service.heartbeat(JOB_ID, 1, 1))
                .isInstanceOfSatisfying(AiJobException.class,
                        failure -> assertThat(failure.kind()).isEqualTo(AiJobException.Kind.STALE_LEASE));
        verify(repository, never()).casHeartbeat(any(), anyLong(), any(), any());
    }

    @Test
    void heartbeatCasConflictRetriesOnceThenStale() {
        AiJobRecord running = job(AiJobStatus.RUNNING, 1, 3, 1, 2, NOW.plusSeconds(60));
        when(repository.require(JOB_ID)).thenReturn(running);
        when(repository.casHeartbeat(any(), anyLong(), any(), any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.heartbeat(JOB_ID, 1, 1))
                .isInstanceOfSatisfying(AiJobException.class,
                        failure -> assertThat(failure.kind()).isEqualTo(AiJobException.Kind.STALE_LEASE));
        verify(repository, times(2)).casHeartbeat(any(), eq(1L), any(), any());
        verify(repository, never()).touchAttemptHeartbeat(any(), anyInt(), anyLong(), any());
    }

    @Test
    void heartbeatPromotesLeasedToRunningAndRenewsExpiry() {
        AiJobRecord leased = job(AiJobStatus.LEASED, 1, 3, 1, 1, NOW.plusSeconds(120));
        AiJobRecord running = job(AiJobStatus.RUNNING, 1, 3, 1, 2, NOW.plusSeconds(120));
        when(repository.require(JOB_ID)).thenReturn(leased);
        when(repository.casHeartbeat(eq(leased), eq(1L), any(), any())).thenReturn(Optional.of(running));

        AiJobService.Heartbeat heartbeat = service.heartbeat(JOB_ID, 1, 1);

        assertThat(heartbeat.status()).isEqualTo(AiJobStatus.RUNNING);
        verify(repository).touchAttemptHeartbeat(JOB_ID, 1, 1, NOW);
        assertThat(counter("ai_job.transitions", "from", "leased", "to", "running")).isEqualTo(1.0);
    }

    @Test
    void heartbeatOnExpiredLeaseMarksItForRecoveryAndRejects() {
        AiJobRecord running = job(AiJobStatus.RUNNING, 1, 3, 1, 2, NOW.minusSeconds(1));
        AiJobRecord retryable = job(AiJobStatus.FAILED_RETRYABLE, 1, 3, 1, 3, null);
        when(repository.require(JOB_ID)).thenReturn(running);
        when(repository.casTransition(any(), any(), anyLong(), any(), any(), any()))
                .thenReturn(Optional.of(retryable));

        assertThatThrownBy(() -> service.heartbeat(JOB_ID, 1, 1))
                .isInstanceOfSatisfying(AiJobException.class,
                        failure -> assertThat(failure.kind()).isEqualTo(AiJobException.Kind.STALE_LEASE));
        assertThat(counter("ai_job.lease_expired_total")).isEqualTo(1.0);
    }

    // ------------------------------------------------------------------
    // 结果幂等 / 失败分类
    // ------------------------------------------------------------------

    @Test
    void firstResultsSucceedJobAndDuplicatesAreIdempotent() {
        AiJobRecord running = job(AiJobStatus.RUNNING, 1, 3, 1, 2, NOW.plusSeconds(60));
        AiJobRecord succeeded = job(AiJobStatus.SUCCEEDED, 1, 3, 1, 3, null);
        when(repository.require(JOB_ID)).thenReturn(running, succeeded);
        when(repository.casTransition(any(), any(), anyLong(), any(), any(), any()))
                .thenReturn(Optional.of(succeeded));
        when(repository.appendResult(eq(JOB_ID), eq(1), eq("MEMORY_CANDIDATE"), eq(DEDUPE),
                any(), any(), any(), any()))
                .thenReturn(new AiJobRepository.ResultWrite(true, result()))
                .thenReturn(new AiJobRepository.ResultWrite(false, result()));

        AiJobService.ResultsOutcome first = service.submitResults(JOB_ID, 1, 1,
                List.of(candidate()));
        AiJobService.ResultsOutcome second = service.submitResults(JOB_ID, 1, 1,
                List.of(candidate()));

        assertThat(first.accepted()).isEqualTo(1);
        assertThat(first.duplicates()).isZero();
        assertThat(first.status()).isEqualTo(AiJobStatus.SUCCEEDED);
        assertThat(second.accepted()).isZero();
        assertThat(second.duplicates()).isEqualTo(1);
        assertThat(second.status()).isEqualTo(AiJobStatus.SUCCEEDED);
        verify(repository, times(1)).casTransition(any(), eq(AiJobStatus.SUCCEEDED), eq(1L),
                any(), any(), any());
        verify(repository).finishAttempt(JOB_ID, 1, AiJobAttemptStatus.SUCCEEDED, null, NOW);
        assertThat(counter("ai_job.success_total")).isEqualTo(1.0);
        assertThat(counter("ai_job.results_total")).isEqualTo(1.0);
        assertThat(counter("ai_job.result_duplicate_total", "result", "duplicate")).isEqualTo(1.0);
    }

    @Test
    void resultsWithStaleEpochAreRejectedBeforeAnyWrite() {
        when(repository.require(JOB_ID)).thenReturn(job(AiJobStatus.RUNNING, 1, 3, 2, 2, NOW.plusSeconds(60)));

        assertThatThrownBy(() -> service.submitResults(JOB_ID, 1, 1, List.of(candidate())))
                .isInstanceOfSatisfying(AiJobException.class,
                        failure -> assertThat(failure.kind()).isEqualTo(AiJobException.Kind.STALE_LEASE));
        verify(repository, never()).appendResult(any(), anyInt(), any(), any(), any(), any(),
                any(), any());
    }

    @Test
    void resultsFromLeasedJobImplicitlyStartRunningBeforeSuccess() {
        AiJobRecord leased = job(AiJobStatus.LEASED, 1, 3, 1, 1, NOW.plusSeconds(60));
        AiJobRecord running = job(AiJobStatus.RUNNING, 1, 3, 1, 2, NOW.plusSeconds(60));
        AiJobRecord succeeded = job(AiJobStatus.SUCCEEDED, 1, 3, 1, 3, null);
        when(repository.require(JOB_ID)).thenReturn(leased);
        when(repository.casTransition(any(), eq(AiJobStatus.RUNNING), anyLong(), any(), any(), any()))
                .thenReturn(Optional.of(running));
        when(repository.casTransition(any(), eq(AiJobStatus.SUCCEEDED), anyLong(), any(), any(), any()))
                .thenReturn(Optional.of(succeeded));
        when(repository.appendResult(any(), anyInt(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new AiJobRepository.ResultWrite(true, result()));

        AiJobService.ResultsOutcome outcome = service.submitResults(JOB_ID, 1, 1,
                List.of(candidate()));

        assertThat(outcome.status()).isEqualTo(AiJobStatus.SUCCEEDED);
        assertThat(counter("ai_job.transitions", "from", "leased", "to", "running")).isEqualTo(1.0);
        assertThat(counter("ai_job.transitions", "from", "running", "to", "succeeded")).isEqualTo(1.0);
    }

    @Test
    void retryableFailureStaysRetryableUntilAttemptsExhausted() {
        AiJobRecord running = job(AiJobStatus.RUNNING, 1, 3, 1, 2, NOW.plusSeconds(60));
        AiJobRecord retryable = job(AiJobStatus.FAILED_RETRYABLE, 1, 3, 1, 3, null);
        when(repository.require(JOB_ID)).thenReturn(running);
        when(repository.casTransition(any(), eq(AiJobStatus.FAILED_RETRYABLE), anyLong(), any(), any(), any()))
                .thenReturn(Optional.of(retryable));

        AiJobRecord failed = service.fail(JOB_ID, 1, 1, true, "TIMEOUT");

        assertThat(failed.status()).isEqualTo(AiJobStatus.FAILED_RETRYABLE);
        ArgumentCaptor<OffsetDateTime> nextAttempt = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(repository).casTransition(any(), eq(AiJobStatus.FAILED_RETRYABLE), eq(1L),
                eq("TIMEOUT"), nextAttempt.capture(), eq(NOW));
        assertThat(nextAttempt.getValue()).isEqualTo(NOW.plusSeconds(30));
        verify(repository).finishAttempt(JOB_ID, 1, AiJobAttemptStatus.FAILED_RETRYABLE,
                "TIMEOUT", NOW);
        assertThat(counter("ai_job.failure_total", "retryable", "true")).isEqualTo(1.0);
    }

    @Test
    void retryableFailureAtMaxAttemptsBecomesFailed() {
        AiJobRecord running = job(AiJobStatus.RUNNING, 3, 3, 1, 2, NOW.plusSeconds(60));
        AiJobRecord failed = job(AiJobStatus.FAILED, 3, 3, 1, 3, null);
        when(repository.require(JOB_ID)).thenReturn(running);
        when(repository.casTransition(any(), eq(AiJobStatus.FAILED), anyLong(), any(), any(), any()))
                .thenReturn(Optional.of(failed));

        AiJobRecord result = service.fail(JOB_ID, 3, 1, true, "TIMEOUT");

        assertThat(result.status()).isEqualTo(AiJobStatus.FAILED);
        verify(repository).casTransition(any(), eq(AiJobStatus.FAILED), eq(1L), eq("TIMEOUT"),
                isNull(), eq(NOW));
        verify(repository).finishAttempt(JOB_ID, 3, AiJobAttemptStatus.FAILED, "TIMEOUT", NOW);
        assertThat(counter("ai_job.failure_total", "retryable", "false")).isEqualTo(1.0);
    }

    @Test
    void fatalFailureGoesFailedImmediately() {
        AiJobRecord running = job(AiJobStatus.RUNNING, 1, 3, 1, 2, NOW.plusSeconds(60));
        AiJobRecord failed = job(AiJobStatus.FAILED, 1, 3, 1, 3, null);
        when(repository.require(JOB_ID)).thenReturn(running);
        when(repository.casTransition(any(), eq(AiJobStatus.FAILED), anyLong(), any(), any(), any()))
                .thenReturn(Optional.of(failed));

        assertThat(service.fail(JOB_ID, 1, 1, false, "SCHEMA_VIOLATION").status())
                .isEqualTo(AiJobStatus.FAILED);
        verify(repository).finishAttempt(JOB_ID, 1, AiJobAttemptStatus.FAILED,
                "SCHEMA_VIOLATION", NOW);
    }

    // ------------------------------------------------------------------
    // 过期 / 恢复
    // ------------------------------------------------------------------

    @Test
    void recoveryExpiresStaleLeasesAndCountsMetric() {
        AiJobRecord retryableJob = job(AiJobStatus.RUNNING, 1, 3, 1, 2, NOW.minusSeconds(5));
        AiJobRecord exhaustedJob = job(AiJobStatus.RUNNING, 3, 3, 3, 4, NOW.minusSeconds(5));
        when(repository.findLeaseExpired(NOW, 100))
                .thenReturn(List.of(retryableJob, exhaustedJob));
        when(repository.findDeadlineExceeded(NOW, 100)).thenReturn(List.of());
        when(repository.findRetryDue(NOW, 100)).thenReturn(List.of());
        when(repository.casTransition(any(), any(), anyLong(), any(), any(), any()))
                .thenAnswer(invocation -> Optional.of(updated(invocation.getArgument(0),
                        invocation.getArgument(1))));

        AiJobService.RecoverySummary summary = service.recoverExpired(NOW, 100);

        assertThat(summary.leaseExpired()).isEqualTo(2);
        assertThat(counter("ai_job.lease_expired_total")).isEqualTo(2.0);
        verify(repository, times(2)).finishAttempt(any(), anyInt(), any(), eq("LEASE_EXPIRED"), eq(NOW));
    }

    @Test
    void retryDueJobsAreRequeued() {
        AiJobRecord retryable = job(AiJobStatus.FAILED_RETRYABLE, 1, 3, 1, 3, null);
        when(repository.findLeaseExpired(NOW, 100)).thenReturn(List.of());
        when(repository.findDeadlineExceeded(NOW, 100)).thenReturn(List.of());
        when(repository.findRetryDue(NOW, 100)).thenReturn(List.of(retryable));
        when(repository.casTransition(any(), eq(AiJobStatus.QUEUED), eq(1L), isNull(), isNull(), eq(NOW)))
                .thenReturn(Optional.of(updated(retryable, AiJobStatus.QUEUED)));

        AiJobService.RecoverySummary summary = service.recoverExpired(NOW, 100);

        assertThat(summary.requeued()).isEqualTo(1);
        assertThat(counter("ai_job.transitions", "from", "failed_retryable", "to", "queued"))
                .isEqualTo(1.0);
    }

    @Test
    void deadlineExceededJobsExpire() {
        AiJobRecord running = job(AiJobStatus.RUNNING, 1, 3, 1, 2, NOW.plusSeconds(10));
        when(repository.findLeaseExpired(NOW, 100)).thenReturn(List.of());
        when(repository.findDeadlineExceeded(NOW, 100)).thenReturn(List.of(running));
        when(repository.findRetryDue(NOW, 100)).thenReturn(List.of());
        when(repository.casTransition(any(), eq(AiJobStatus.EXPIRED), anyLong(), any(), any(), any()))
                .thenReturn(Optional.of(updated(running, AiJobStatus.EXPIRED)));

        AiJobService.RecoverySummary summary = service.recoverExpired(NOW, 100);

        assertThat(summary.deadlineExpired()).isEqualTo(1);
        verify(repository).finishAttempt(JOB_ID, 1, AiJobAttemptStatus.EXPIRED,
                "DEADLINE_EXCEEDED", NOW);
    }

    @Test
    void retryBackoffIsExponentialWithTenMinuteCap() {
        assertThat(AiJobService.backoffSeconds(1)).isEqualTo(30);
        assertThat(AiJobService.backoffSeconds(2)).isEqualTo(120);
        assertThat(AiJobService.backoffSeconds(3)).isEqualTo(480);
        assertThat(AiJobService.backoffSeconds(4)).isEqualTo(600);
        assertThat(AiJobService.backoffSeconds(99)).isEqualTo(600);
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private double counter(String name, String... tags) {
        Counter counter = registry.find(name).tags(tags).counter();
        return counter == null ? 0.0 : counter.count();
    }

    private static AiJobCandidate candidate() {
        return new AiJobCandidate("DECISION", "发布窗口", "周五发布", new BigDecimal("0.8"),
                "fact:project", DEDUPE);
    }

    private static AiJobResultRecord result() {
        return new AiJobResultRecord(UUID.randomUUID(), JOB_ID, 1, "MEMORY_CANDIDATE", DEDUPE,
                "{}", "PENDING_REVIEW", "sha256:" + "b".repeat(32), "[\"fact:project\"]", NOW);
    }

    private static AiJobRepository.AttemptSnapshot attemptSnapshot() {
        String payload = """
                {"jobId":"%s","projectId":"%s","attempt":1,"leaseEpoch":1,\
                "facts":[{"ref":"fact:project","kind":"PROJECT","text":"项目"}],\
                "existingMemories":[]}
                """.formatted(JOB_ID, PROJECT_ID).strip();
        return new AiJobRepository.AttemptSnapshot(JOB_ID, PROJECT_ID, 1, 1,
                hash(payload), "[\"fact:project\"]", payload);
    }

    private static String hash(String payload) {
        try {
            return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(payload.getBytes(StandardCharsets.UTF_8))).substring(0, 32);
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static AiJobRecord job(AiJobStatus status, int attemptCount, int maxAttempts,
            long leaseEpoch, int version, OffsetDateTime leaseExpiresAt) {
        return new AiJobRecord(JOB_ID, PROJECT_ID, "MEMORY_EXTRACTION", status, 1, "{}",
                maxAttempts, attemptCount, leaseEpoch, UUID.randomUUID(), leaseExpiresAt,
                null, null, null, CREATED_BY, NOW.minusSeconds(60), NOW, version);
    }

    private static AiJobRecord updated(AiJobRecord job, AiJobStatus status) {
        return new AiJobRecord(job.id(), job.projectId(), job.jobType(), status,
                job.schemaVersion(), job.payloadJson(), job.maxAttempts(), job.attemptCount(),
                job.leaseEpoch(), null, null, null, job.deadlineAt(), job.lastErrorCode(),
                job.createdBy(), job.createdAt(), job.updatedAt(), job.version() + 1);
    }
}

