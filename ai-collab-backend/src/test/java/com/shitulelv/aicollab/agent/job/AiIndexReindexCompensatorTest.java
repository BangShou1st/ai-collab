package com.shitulelv.aicollab.agent.job;

import com.shitulelv.aicollab.internal.ai.index.IndexAdminClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** {@link AiIndexReindexCompensator} 与补偿扫描联动单元测试（不依赖 Docker）。 */
class AiIndexReindexCompensatorTest {

    private static final UUID PARENT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID GEN_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID PROJECT_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID DOC_1 = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final UUID DOC_2 = UUID.fromString("55555555-5555-5555-5555-555555555555");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-27T08:00:00Z"), ZoneOffset.UTC);
    private static final OffsetDateTime NOW = OffsetDateTime.now(CLOCK);

    private AiJobRepository repository;
    private AiIndexJobStore store;
    private AiIndexJobService indexJobs;
    private IndexAdminClient indexAdmin;
    private AiIndexReindexCompensator compensator;

    @BeforeEach
    void setUp() {
        repository = mock(AiJobRepository.class);
        store = mock(AiIndexJobStore.class);
        indexJobs = mock(AiIndexJobService.class);
        indexAdmin = mock(IndexAdminClient.class);
        compensator = new AiIndexReindexCompensator(repository, store, indexJobs, indexAdmin,
                org.mockito.Mockito.mock(com.shitulelv.aicollab.infrastructure.ai.embedding.SystemEmbeddingConfigRepository.class),
                CLOCK);
    }

    @Test
    void terminalFailedOrExpiredOrCanceledChildAbandonsGenerationAndMarksAbandoned() {
        AiJobRecord parent = parent(4);
        AiJobRecord abandoned = parent(5);
        for (AiJobStatus terminal : List.of(AiJobStatus.FAILED, AiJobStatus.EXPIRED, AiJobStatus.CANCELED)) {
            setUp();
            when(repository.findReindexFannedOut(100)).thenReturn(List.of(parent));
            when(repository.childStatusCounts(PARENT_ID)).thenReturn(Map.of(
                    AiJobStatus.SUCCEEDED, 5, terminal, 1));
            when(repository.casUpdateIndexPhase(PARENT_ID, 4, "FANNED_OUT", "ABANDONED", NOW))
                    .thenReturn(Optional.of(abandoned));

            AiIndexReindexCompensator.AdvanceSummary summary = compensator.advanceFannedOut(100);

            assertThat(summary.abandoned()).isOne();
            verify(indexAdmin).abandon(GEN_ID);
            verify(indexAdmin, never()).activate(any());
        }
    }

    @Test
    void inFlightChildWaitsWithoutCallingAdmin() {
        AiJobRecord parent = parent(4);
        when(repository.findReindexFannedOut(100)).thenReturn(List.of(parent));
        when(repository.childStatusCounts(PARENT_ID)).thenReturn(Map.of(
                AiJobStatus.SUCCEEDED, 3, AiJobStatus.FAILED_RETRYABLE, 1));

        AiIndexReindexCompensator.AdvanceSummary summary = compensator.advanceFannedOut(100);

        assertThat(summary.idle()).isTrue();
        verify(indexAdmin, never()).abandon(any());
        verify(indexAdmin, never()).reconcileMissing(any(), any());
        verify(indexAdmin, never()).activate(any());
    }

    @Test
    void missingDocsAfterAllChildrenSucceededDispatchesMissingWithoutActivating() {
        AiJobRecord parent = parent(4);
        AiIndexJobStore.DocumentRef ref1 = new AiIndexJobStore.DocumentRef(DOC_1, PROJECT_ID);
        AiIndexJobStore.DocumentRef ref2 = new AiIndexJobStore.DocumentRef(DOC_2, PROJECT_ID);
        when(repository.findReindexFannedOut(100)).thenReturn(List.of(parent));
        when(repository.childStatusCounts(PARENT_ID)).thenReturn(Map.of(AiJobStatus.SUCCEEDED, 1));
        when(store.listReadyDocuments(null, IndexAdminClient.MAX_BATCH_DOCUMENTS)).thenReturn(List.of(ref1, ref2));
        when(indexAdmin.reconcileMissing(GEN_ID, List.of(DOC_1, DOC_2))).thenReturn(List.of(DOC_2));

        AiIndexReindexCompensator.AdvanceSummary summary = compensator.advanceFannedOut(100);

        assertThat(summary.reconciledDispatched()).isOne();
        assertThat(summary.activated()).isZero();
        verify(indexJobs).enqueueReindexChildren(PARENT_ID, GEN_ID, List.of(ref2));
        verify(indexAdmin, never()).activate(any());
        verify(repository, never()).casUpdateIndexPhase(any(), anyInt(), any(), any(), any());
    }

    @Test
    void noMissingDocsActivatesGenerationAndMarksActivated() {
        AiJobRecord parent = parent(4);
        AiJobRecord activated = parent(5);
        AiIndexJobStore.DocumentRef ref1 = new AiIndexJobStore.DocumentRef(DOC_1, PROJECT_ID);
        when(repository.findReindexFannedOut(100)).thenReturn(List.of(parent));
        when(repository.childStatusCounts(PARENT_ID)).thenReturn(Map.of(AiJobStatus.SUCCEEDED, 1));
        when(store.listReadyDocuments(null, IndexAdminClient.MAX_BATCH_DOCUMENTS)).thenReturn(List.of(ref1));
        when(indexAdmin.reconcileMissing(GEN_ID, List.of(DOC_1))).thenReturn(List.of());
        when(repository.casUpdateIndexPhase(PARENT_ID, 4, "FANNED_OUT", "ACTIVATED", NOW))
                .thenReturn(Optional.of(activated));

        AiIndexReindexCompensator.AdvanceSummary summary = compensator.advanceFannedOut(100);

        assertThat(summary.activated()).isOne();
        verify(indexAdmin).activate(GEN_ID);
        verify(indexJobs, never()).enqueueReindexChildren(any(), any(), any());
    }

    @Test
    void adminErrorLeavesFannedOutForRetryAndCompensationJobDelegates() {
        AiJobRecord parent = parent(4);
        when(repository.findReindexFannedOut(100)).thenReturn(List.of(parent));
        when(repository.childStatusCounts(PARENT_ID)).thenReturn(Map.of(AiJobStatus.FAILED, 1));
        org.mockito.Mockito.doThrow(new IndexAdminClient.IndexAdminException(
                IndexAdminClient.IndexAdminException.Kind.UNAVAILABLE, 503, null, "down"))
                .when(indexAdmin).abandon(GEN_ID);

        AiJobService jobs = mock(AiJobService.class);
        when(jobs.compensate(NOW, AiTaskCompensationJob.SCAN_LIMIT))
                .thenReturn(new AiJobService.CompensationSummary(0, 0, 0, 0));
        AiTaskCompensationJob job = new AiTaskCompensationJob(jobs, mock(RedissonClient.class), CLOCK);
        job.setReindexCompensator(compensator);

        job.scan();

        verify(repository, never()).casUpdateIndexPhase(any(), anyInt(), any(), any(), any());
    }

    private static AiJobRecord parent(int version) {
        return new AiJobRecord(PARENT_ID, null, AiJobTypes.REINDEX_GENERATION, AiJobStatus.SUCCEEDED,
                1, "{}", 3, 1, 0L, null, null, null, null, null, null, NOW, NOW, version,
                null, GEN_ID, null, AiJobTypes.INDEX_PHASE_FANNED_OUT);
    }
}
