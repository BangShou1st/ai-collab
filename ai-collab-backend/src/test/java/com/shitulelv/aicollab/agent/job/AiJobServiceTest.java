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
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AiJobService 单元测试（mock repository）：claim 冲突、heartbeat/result/fail 的 epoch+leaseToken 校验、
 * 终态幂等重放、重试次数与退避、补偿扫描四条规则与"只有 CAS 赢家才重新派发"；同时断言指标口径。
 * 真实并发语义见 {@code AiTaskStreamIntegrationTest}。
 */
class AiJobServiceTest {

    private static final UUID JOB_ID = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd");
    private static final UUID PROJECT_ID = UUID.fromString("eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee");
    private static final UUID CREATED_BY = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff");
    private static final UUID LEASE = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final String DEDUPE = "a".repeat(64);
    private static final Instant NOW_INSTANT = Instant.parse("2026-11-12T04:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW_INSTANT, ZoneOffset.UTC);
    private static final OffsetDateTime NOW = OffsetDateTime.now(CLOCK);

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final List<Object> published = new ArrayList<>();
    private AiJobRepository repository;
    private AiJobService service;

    @BeforeEach
    void setUp() {
        repository = mock(AiJobRepository.class);
        service = new AiJobService(repository, new AiJobProperties(true, 3, 60), registry,
                new com.fasterxml.jackson.databind.ObjectMapper(), published::add, CLOCK);
        when(repository.findAttemptSnapshot(JOB_ID, 1, 0L))
                .thenReturn(Optional.of(attemptSnapshot()));
    }

    // ------------------------------------------------------------------
    // 创建 / claim
    // ------------------------------------------------------------------

    @Test
    void createPublishesQueuedEventForAfterCommitDispatch() {
        AiJobRecord queued = job(AiJobStatus.QUEUED, 0, 3, 0, 0, null, null);
        when(repository.create(PROJECT_ID, "MEMORY_EXTRACTION", "{}", 3, null, CREATED_BY))
                .thenReturn(queued);

        service.create(PROJECT_ID, "{}", CREATED_BY);

        assertThat(published).containsExactly(new AiJobQueuedEvent(queued));
    }

    @Test
    void claimLeasesWithoutChangingEpochAndRecordsTransition() {
        AiJobRecord queued = job(AiJobStatus.QUEUED, 0, 3, 4, 0, null, null);
        AiJobRecord leased = job(AiJobStatus.LEASED, 1, 3, 4, 1, LEASE, NOW.plusSeconds(60));
        when(repository.find(JOB_ID)).thenReturn(Optional.of(queued));
        when(repository.casClaim(eq(JOB_ID), eq(4L), any(UUID.class), eq(NOW), eq(NOW.plusSeconds(60))))
                .thenReturn(Optional.of(leased));

        AiJobService.Lease lease = service.claim(JOB_ID, "MEMORY_EXTRACTION", 4L);

        assertThat(lease.attempt()).isEqualTo(1);
        assertThat(lease.leaseEpoch()).isEqualTo(4);
        assertThat(lease.leaseToken()).isEqualTo(LEASE);
        verify(repository).insertAttempt(JOB_ID, 1, 4, NOW);
        assertThat(counter("ai_job.transitions", "from", "queued", "to", "leased")).isEqualTo(1.0);
    }

    @Test
    void claimConflictWhenConditionFailsOrTaskTypeMismatchesOrTaskMissing() {
        AiJobRecord queued = job(AiJobStatus.QUEUED, 0, 3, 0, 0, null, null);
        when(repository.find(JOB_ID)).thenReturn(Optional.of(queued));
        when(repository.casClaim(any(), anyLong(), any(), any(), any())).thenReturn(Optional.empty());

        assertConflict(() -> service.claim(JOB_ID, "MEMORY_EXTRACTION", 0L));
        assertConflict(() -> service.claim(JOB_ID, "OTHER_TYPE", 0L));
        when(repository.find(JOB_ID)).thenReturn(Optional.empty());
        assertConflict(() -> service.claim(JOB_ID, "MEMORY_EXTRACTION", 0L));
        verify(repository, never()).insertAttempt(any(), anyInt(), anyLong(), any());
    }

    // ------------------------------------------------------------------
    // heartbeat
    // ------------------------------------------------------------------

    @Test
    void heartbeatRejectsWrongEpochOrLeaseTokenWithoutWriting() {
        when(repository.find(JOB_ID)).thenReturn(Optional.of(
                job(AiJobStatus.RUNNING, 1, 3, 2, 2, LEASE, NOW.plusSeconds(60))));

        assertStale(() -> service.heartbeat(JOB_ID, 1L, LEASE));
        assertStale(() -> service.heartbeat(JOB_ID, 2L, UUID.randomUUID()));
        assertStale(() -> service.heartbeat(JOB_ID, 2L, null));
        verify(repository, never()).casHeartbeat(any(), anyLong(), any(), any(), any());
    }

    @Test
    void heartbeatPromotesLeasedToRunningAndRenewsLease() {
        AiJobRecord leased = job(AiJobStatus.LEASED, 1, 3, 0, 1, LEASE, NOW.plusSeconds(10));
        AiJobRecord running = job(AiJobStatus.RUNNING, 1, 3, 0, 2, LEASE, NOW.plusSeconds(60));
        when(repository.find(JOB_ID)).thenReturn(Optional.of(leased));
        when(repository.casHeartbeat(JOB_ID, 0L, LEASE, NOW.plusSeconds(60), NOW))
                .thenReturn(Optional.of(running));

        AiJobService.Heartbeat heartbeat = service.heartbeat(JOB_ID, 0L, LEASE);

        assertThat(heartbeat.job().status()).isEqualTo(AiJobStatus.RUNNING);
        assertThat(heartbeat.cancelRequested()).isFalse();
        verify(repository).touchAttemptHeartbeat(JOB_ID, 1, 0L, NOW);
        assertThat(counter("ai_job.transitions", "from", "leased", "to", "running")).isEqualTo(1.0);
    }

    @Test
    void heartbeatOnCanceledTaskRequestsCancellation() {
        when(repository.find(JOB_ID)).thenReturn(Optional.of(
                job(AiJobStatus.CANCELED, 1, 3, 0, 3, LEASE, null)));

        assertThat(service.heartbeat(JOB_ID, 0L, LEASE).cancelRequested()).isTrue();
        verify(repository, never()).casHeartbeat(any(), anyLong(), any(), any(), any());
    }

    @Test
    void heartbeatAfterTerminalStateOrLostCasIsStale() {
        when(repository.find(JOB_ID)).thenReturn(Optional.of(
                job(AiJobStatus.SUCCEEDED, 1, 3, 0, 3, LEASE, null)));
        assertStale(() -> service.heartbeat(JOB_ID, 0L, LEASE));

        when(repository.find(JOB_ID)).thenReturn(Optional.of(
                job(AiJobStatus.RUNNING, 1, 3, 0, 2, LEASE, NOW.plusSeconds(30))));
        when(repository.casHeartbeat(any(), anyLong(), any(), any(), any())).thenReturn(Optional.empty());
        assertStale(() -> service.heartbeat(JOB_ID, 0L, LEASE));
        verify(repository, never()).touchAttemptHeartbeat(any(), anyInt(), anyLong(), any());
    }

    // ------------------------------------------------------------------
    // result / fail
    // ------------------------------------------------------------------

    @Test
    void resultSucceedsOnceAndReplayOnTerminalStateWritesNothing() {
        AiJobRecord running = job(AiJobStatus.RUNNING, 1, 3, 0, 2, LEASE, NOW.plusSeconds(60));
        AiJobRecord succeeded = job(AiJobStatus.SUCCEEDED, 1, 3, 0, 3, LEASE, null);
        when(repository.find(JOB_ID)).thenReturn(Optional.of(running), Optional.of(succeeded));
        when(repository.casTransition(any(), eq(AiJobStatus.SUCCEEDED), eq(0L), any(), any(), any()))
                .thenReturn(Optional.of(succeeded));
        when(repository.appendResult(eq(JOB_ID), eq(1), eq("MEMORY_CANDIDATE"), eq(DEDUPE),
                any(), any(), any(), any()))
                .thenReturn(new AiJobRepository.ResultWrite(true, result()));

        AiJobService.ResultsOutcome first = service.submitResult(JOB_ID, 0L, LEASE, List.of(candidate()));
        AiJobService.ResultsOutcome replay = service.submitResult(JOB_ID, 0L, LEASE, List.of(candidate()));

        assertThat(first.status()).isEqualTo(AiJobStatus.SUCCEEDED);
        assertThat(first.accepted()).isOne();
        assertThat(first.replayed()).isFalse();
        assertThat(replay.status()).isEqualTo(AiJobStatus.SUCCEEDED);
        assertThat(replay.replayed()).isTrue();
        verify(repository).appendResult(any(), anyInt(), any(), any(), any(), any(), any(), any());
        verify(repository).finishAttempt(JOB_ID, 1, AiJobAttemptStatus.SUCCEEDED, null, NOW);
        assertThat(counter("ai_job.success_total")).isEqualTo(1.0);
        assertThat(counter("ai_job.results_total")).isEqualTo(1.0);
    }

    @Test
    void resultWithStaleEpochOrForeignLeaseTokenIsRejectedBeforeAnyWrite() {
        when(repository.find(JOB_ID)).thenReturn(Optional.of(
                job(AiJobStatus.RUNNING, 1, 3, 1, 2, LEASE, NOW.plusSeconds(60))));

        assertStale(() -> service.submitResult(JOB_ID, 0L, LEASE, List.of(candidate())));
        assertStale(() -> service.submitResult(JOB_ID, 1L, UUID.randomUUID(), List.of(candidate())));
        verify(repository, never()).appendResult(any(), anyInt(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void resultFromLeasedTaskImplicitlyStartsRunning() {
        AiJobRecord leased = job(AiJobStatus.LEASED, 1, 3, 0, 1, LEASE, NOW.plusSeconds(60));
        AiJobRecord running = job(AiJobStatus.RUNNING, 1, 3, 0, 2, LEASE, NOW.plusSeconds(60));
        AiJobRecord succeeded = job(AiJobStatus.SUCCEEDED, 1, 3, 0, 3, LEASE, null);
        when(repository.find(JOB_ID)).thenReturn(Optional.of(leased));
        when(repository.casTransition(any(), eq(AiJobStatus.RUNNING), anyLong(), any(), any(), any()))
                .thenReturn(Optional.of(running));
        when(repository.casTransition(any(), eq(AiJobStatus.SUCCEEDED), anyLong(), any(), any(), any()))
                .thenReturn(Optional.of(succeeded));
        when(repository.appendResult(any(), anyInt(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new AiJobRepository.ResultWrite(true, result()));

        assertThat(service.submitResult(JOB_ID, 0L, LEASE, List.of(candidate())).status())
                .isEqualTo(AiJobStatus.SUCCEEDED);
        assertThat(counter("ai_job.transitions", "from", "leased", "to", "running")).isEqualTo(1.0);
        assertThat(counter("ai_job.transitions", "from", "running", "to", "succeeded")).isEqualTo(1.0);
    }

    @Test
    void retryableFailureBecomesRetryableWithBackoff() {
        AiJobRecord running = job(AiJobStatus.RUNNING, 1, 3, 0, 2, LEASE, NOW.plusSeconds(60));
        AiJobRecord retryable = job(AiJobStatus.FAILED_RETRYABLE, 1, 3, 0, 3, LEASE, null);
        when(repository.find(JOB_ID)).thenReturn(Optional.of(running));
        when(repository.casTransition(any(), eq(AiJobStatus.FAILED_RETRYABLE), anyLong(), any(), any(), any()))
                .thenReturn(Optional.of(retryable));

        assertThat(service.fail(JOB_ID, 0L, LEASE, true, "provider_timeout").status())
                .isEqualTo(AiJobStatus.FAILED_RETRYABLE);
        ArgumentCaptor<OffsetDateTime> nextAttempt = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(repository).casTransition(any(), eq(AiJobStatus.FAILED_RETRYABLE), eq(0L),
                eq("provider_timeout"), nextAttempt.capture(), eq(NOW));
        assertThat(nextAttempt.getValue()).isEqualTo(NOW.plusSeconds(30));
        verify(repository).finishAttempt(JOB_ID, 1, AiJobAttemptStatus.FAILED_RETRYABLE,
                "provider_timeout", NOW);
        assertThat(counter("ai_job.failure_total", "retryable", "true")).isEqualTo(1.0);
    }

    @Test
    void retryableFailureAtMaxAttemptsAndNonRetryableFailureBecomeFailed() {
        AiJobRecord exhausted = job(AiJobStatus.RUNNING, 3, 3, 2, 2, LEASE, NOW.plusSeconds(60));
        AiJobRecord failed = job(AiJobStatus.FAILED, 3, 3, 2, 3, LEASE, null);
        when(repository.find(JOB_ID)).thenReturn(Optional.of(exhausted));
        when(repository.casTransition(any(), eq(AiJobStatus.FAILED), anyLong(), any(), any(), any()))
                .thenReturn(Optional.of(failed));

        assertThat(service.fail(JOB_ID, 2L, LEASE, true, "provider_timeout").status())
                .isEqualTo(AiJobStatus.FAILED);
        verify(repository).casTransition(any(), eq(AiJobStatus.FAILED), eq(2L), eq("provider_timeout"),
                isNull(), eq(NOW));

        AiJobRecord fresh = job(AiJobStatus.RUNNING, 1, 3, 0, 2, LEASE, NOW.plusSeconds(60));
        when(repository.find(JOB_ID)).thenReturn(Optional.of(fresh));
        assertThat(service.fail(JOB_ID, 0L, LEASE, false, "CANCELED").status())
                .isEqualTo(AiJobStatus.FAILED);
        assertThat(counter("ai_job.failure_total", "retryable", "false")).isEqualTo(2.0);
    }

    @Test
    void failReplayOnTerminalStateReturnsCurrentStatusWithoutWriting() {
        when(repository.find(JOB_ID)).thenReturn(Optional.of(
                job(AiJobStatus.FAILED, 1, 3, 0, 4, LEASE, null)));

        assertThat(service.fail(JOB_ID, 0L, LEASE, false, "X").status()).isEqualTo(AiJobStatus.FAILED);
        verify(repository, never()).casTransition(any(), any(), anyLong(), any(), any(), any());
        verify(repository, never()).finishAttempt(any(), anyInt(), any(), any(), any());
    }

    // ------------------------------------------------------------------
    // 补偿扫描（契约 §4.4）
    // ------------------------------------------------------------------

    @Test
    void compensationReclaimsExpiredLeaseWithEpochBumpAndRedispatches() {
        AiJobRecord expired = job(AiJobStatus.RUNNING, 1, 3, 0, 2, LEASE, NOW.minusSeconds(5));
        AiJobRecord requeued = job(AiJobStatus.QUEUED, 1, 3, 1, 3, null, null);
        stubScans(List.of(), List.of(expired), List.of(), List.of());
        when(repository.casFence(expired, AiJobStatus.QUEUED, "LEASE_EXPIRED", NOW))
                .thenReturn(Optional.of(requeued));

        AiJobService.CompensationSummary summary = service.compensate(NOW, 100);

        assertThat(summary.requeued()).isOne();
        assertThat(published).containsExactly(new AiJobQueuedEvent(requeued));
        verify(repository).finishAttempt(JOB_ID, 1, AiJobAttemptStatus.FAILED_RETRYABLE,
                "LEASE_EXPIRED", NOW);
        assertThat(counter("ai_job.lease_expired_total")).isEqualTo(1.0);
    }

    @Test
    void compensationFailsTaskWhoseAttemptsAreExhausted() {
        AiJobRecord exhausted = job(AiJobStatus.LEASED, 3, 3, 2, 2, LEASE, NOW.minusSeconds(5));
        stubScans(List.of(), List.of(exhausted), List.of(), List.of());
        when(repository.casFence(exhausted, AiJobStatus.FAILED, "LEASE_EXPIRED", NOW))
                .thenReturn(Optional.of(job(AiJobStatus.FAILED, 3, 3, 3, 3, null, null)));

        assertThat(service.compensate(NOW, 100).exhausted()).isOne();
        assertThat(published).isEmpty();
        verify(repository).finishAttempt(JOB_ID, 3, AiJobAttemptStatus.FAILED, "LEASE_EXPIRED", NOW);
    }

    @Test
    void compensationRequeuesDueRetryAndExpiresPastDeadline() {
        AiJobRecord retryDue = job(AiJobStatus.FAILED_RETRYABLE, 1, 3, 0, 3, LEASE, null);
        AiJobRecord pastDeadline = job(AiJobStatus.QUEUED, 0, 3, 0, 0, null, null);
        AiJobRecord requeued = job(AiJobStatus.QUEUED, 1, 3, 1, 4, null, null);
        stubScans(List.of(pastDeadline), List.of(), List.of(retryDue), List.of());
        when(repository.casFence(pastDeadline, AiJobStatus.EXPIRED, "DEADLINE_EXCEEDED", NOW))
                .thenReturn(Optional.of(job(AiJobStatus.EXPIRED, 0, 3, 1, 1, null, null)));
        when(repository.casFence(retryDue, AiJobStatus.QUEUED, null, NOW)).thenReturn(Optional.of(requeued));

        AiJobService.CompensationSummary summary = service.compensate(NOW, 100);

        assertThat(summary.expired()).isOne();
        assertThat(summary.requeued()).isOne();
        assertThat(published).containsExactly(new AiJobQueuedEvent(requeued));
        assertThat(counter("ai_job.transitions", "from", "failed_retryable", "to", "queued")).isEqualTo(1.0);
    }

    @Test
    void compensationRedispatchesStaleQueuedWithOriginalEpochOnlyForCasWinner() {
        AiJobRecord stale = job(AiJobStatus.QUEUED, 0, 3, 5, 0, null, null);
        AiJobRecord touched = job(AiJobStatus.QUEUED, 0, 3, 5, 1, null, null);
        stubScans(List.of(), List.of(), List.of(), List.of(stale));
        when(repository.casTouchQueued(stale, NOW)).thenReturn(Optional.of(touched), Optional.empty());

        assertThat(service.compensate(NOW, 100).redispatched()).isOne();
        assertThat(service.compensate(NOW, 100).redispatched()).isZero();
        assertThat(published).containsExactly(new AiJobQueuedEvent(touched));
        assertThat(touched.leaseEpoch()).isEqualTo(5L);
        verify(repository, never()).casFence(any(), any(), any(), any());
    }

    @Test
    void compensationLoserOfFenceCasDoesNotRedispatch() {
        AiJobRecord expired = job(AiJobStatus.RUNNING, 1, 3, 0, 2, LEASE, NOW.minusSeconds(5));
        stubScans(List.of(), List.of(expired), List.of(), List.of());
        when(repository.casFence(any(), any(), any(), any())).thenReturn(Optional.empty());

        assertThat(service.compensate(NOW, 100).idle()).isTrue();
        assertThat(published).isEmpty();
        verify(repository, never()).finishAttempt(any(), anyInt(), any(), any(), any());
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

    private void stubScans(List<AiJobRecord> deadline, List<AiJobRecord> leaseExpired,
            List<AiJobRecord> retryDue, List<AiJobRecord> staleQueued) {
        when(repository.findDeadlineExceeded(NOW, 100)).thenReturn(deadline);
        when(repository.findLeaseExpired(NOW, 100)).thenReturn(leaseExpired);
        when(repository.findRetryDue(NOW, 100)).thenReturn(retryDue);
        when(repository.findStaleQueued(NOW.minusSeconds(60), NOW, 100)).thenReturn(staleQueued);
    }

    private static void assertStale(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(AiJobException.class,
                failure -> assertThat(failure.kind()).isEqualTo(AiJobException.Kind.STALE_LEASE));
    }

    private static void assertConflict(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(AiJobException.class,
                failure -> assertThat(failure.kind()).isEqualTo(AiJobException.Kind.CLAIM_CONFLICT));
    }

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
                {"jobId":"%s","projectId":"%s","attempt":1,"leaseEpoch":0,\
                "facts":[{"ref":"fact:project","kind":"PROJECT","text":"项目"}],\
                "existingMemories":[]}
                """.formatted(JOB_ID, PROJECT_ID).strip();
        return new AiJobRepository.AttemptSnapshot(JOB_ID, PROJECT_ID, 1, 0,
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
            long leaseEpoch, int version, UUID leaseToken, OffsetDateTime leaseExpiresAt) {
        return new AiJobRecord(JOB_ID, PROJECT_ID, "MEMORY_EXTRACTION", status, 1, "{}",
                maxAttempts, attemptCount, leaseEpoch, leaseToken, leaseExpiresAt,
                null, null, null, CREATED_BY, NOW.minusSeconds(60), NOW, version);
    }
}
