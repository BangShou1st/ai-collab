package com.shitulelv.aicollab.agent.job;

import com.shitulelv.aicollab.internal.ai.index.IndexAdminClient;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** {@link AiIndexPruneService} 与 {@link AiIndexPruneJob} 单元测试（不依赖 Docker）。 */
class AiIndexPruneServiceTest {

    private static final UUID GEN_ACTIVE = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID GEN_BUILDING = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final UUID GEN_RETIRED = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
    private static final UUID GEN_FAILED = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd");
    private static final UUID DOC_1 = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID DOC_2 = UUID.fromString("80000000-0000-0000-0000-000000000002");
    private static final OffsetDateTime DB_NOW = OffsetDateTime.of(2026, 11, 27, 8, 0, 0, 0, ZoneOffset.UTC);

    private AiIndexJobStore store;
    private IndexAdminClient indexAdmin;
    private SimpleMeterRegistry meters;
    private AiIndexPruneService service;

    @BeforeEach
    void setUp() {
        store = mock(AiIndexJobStore.class);
        indexAdmin = mock(IndexAdminClient.class);
        meters = new SimpleMeterRegistry();
        service = new AiIndexPruneService(store, indexAdmin, meters);
    }

    @Test
    void prunesOnlyActiveAndBuildingGenerationsWithPgServerNowAndRecordsMetric() {
        when(indexAdmin.listGenerations()).thenReturn(List.of(
                gen(GEN_ACTIVE, "ACTIVE"),
                gen(GEN_BUILDING, "BUILDING"),
                gen(GEN_RETIRED, "RETIRED"),
                gen(GEN_FAILED, "FAILED")));
        IndexAdminClient.DocumentRange batch1Range = new IndexAdminClient.DocumentRange(null, DOC_1);
        IndexAdminClient.DocumentRange batch2Range = new IndexAdminClient.DocumentRange(DOC_1, null);
        when(store.snapshotIndexableForPrune()).thenReturn(new AiIndexJobStore.PruneSnapshot(
                DB_NOW,
                List.of(
                        new AiIndexJobStore.PruneBatch(batch1Range, List.of(DOC_1)),
                        new AiIndexJobStore.PruneBatch(batch2Range, List.of(DOC_2)))));

        when(indexAdmin.reconcilePrune(GEN_ACTIVE, DB_NOW, batch1Range, List.of(DOC_1)))
                .thenReturn(new IndexAdminClient.PruneResult(1, 10));
        when(indexAdmin.reconcilePrune(GEN_ACTIVE, DB_NOW, batch2Range, List.of(DOC_2)))
                .thenReturn(new IndexAdminClient.PruneResult(2, 25));
        when(indexAdmin.reconcilePrune(GEN_BUILDING, DB_NOW, batch1Range, List.of(DOC_1)))
                .thenReturn(new IndexAdminClient.PruneResult(0, 0));
        when(indexAdmin.reconcilePrune(GEN_BUILDING, DB_NOW, batch2Range, List.of(DOC_2)))
                .thenReturn(new IndexAdminClient.PruneResult(1, 5));

        AiIndexPruneService.PruneSummary summary = service.pruneAll();

        assertThat(summary.generationsScanned()).isEqualTo(2);
        assertThat(summary.deletedDocuments()).isEqualTo(4L);
        assertThat(summary.deletedRows()).isEqualTo(40L);
        assertThat(meters.counter(AiIndexPruneService.METRIC_ORPHAN_ROWS_DELETED).count()).isEqualTo(40.0);
        verify(indexAdmin, never()).reconcilePrune(eq(GEN_RETIRED), any(), any(), any());
        verify(indexAdmin, never()).reconcilePrune(eq(GEN_FAILED), any(), any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void storeSnapshotUsesPgServerNowAndOrdersByUuidAscWithKeysetBatching() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        AiIndexJobStore realStore = new AiIndexJobStore(jdbc, (com.shitulelv.aicollab.project.application.service.AuditService) null);

        when(jdbc.queryForObject("SELECT now()", OffsetDateTime.class)).thenReturn(DB_NOW);
        List<UUID> firstPage = new java.util.ArrayList<>(AiIndexJobStore.PRUNE_BATCH_SIZE + 1);
        for (int i = 0; i < AiIndexJobStore.PRUNE_BATCH_SIZE; i++) {
            firstPage.add(DOC_1);
        }
        firstPage.add(DOC_2);
        when(jdbc.query(
                argThat((String sql) -> sql != null && sql.contains("WHERE status IN") && !sql.contains("AND id > ?")),
                any(RowMapper.class), eq(AiIndexJobStore.PRUNE_BATCH_SIZE + 1)))
                .thenReturn(firstPage);
        when(jdbc.query(
                argThat((String sql) -> sql != null && sql.contains("WHERE status IN") && sql.contains("AND id > ?")),
                any(RowMapper.class), eq(DOC_1), eq(AiIndexJobStore.PRUNE_BATCH_SIZE + 1)))
                .thenReturn(List.of(DOC_2));

        AiIndexJobStore.PruneSnapshot snapshot = realStore.snapshotIndexableForPrune();

        assertThat(snapshot.asOf()).isEqualTo(DB_NOW);
        assertThat(snapshot.batches()).hasSize(2);
        assertThat(snapshot.batches().get(0).range()).isEqualTo(new IndexAdminClient.DocumentRange(null, DOC_1));
        assertThat(snapshot.batches().get(0).documentIds()).hasSize(AiIndexJobStore.PRUNE_BATCH_SIZE);
        assertThat(snapshot.batches().get(1).range()).isEqualTo(new IndexAdminClient.DocumentRange(DOC_1, null));
        assertThat(snapshot.batches().get(1).documentIds()).containsExactly(DOC_2);
    }

    @Test
    void pruneJobAcquiresAndReleasesIndependentRedissonLock() throws Exception {
        RedissonClient redisson = mock(RedissonClient.class);
        RLock lock = mock(RLock.class);
        when(redisson.getLock(AiIndexPruneJob.LOCK_KEY)).thenReturn(lock);
        when(lock.tryLock(0, -1, TimeUnit.MILLISECONDS)).thenReturn(true, false);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        when(indexAdmin.listGenerations()).thenReturn(List.of());

        AiIndexPruneJob job = new AiIndexPruneJob(service, redisson);

        assertThat(job.runOnce()).isEqualTo(new AiIndexPruneService.PruneSummary(0, 0, 0));
        assertThat(job.runOnce()).isNull();
        verify(lock).unlock();
    }

    private static IndexAdminClient.GenerationInfo gen(UUID id, String status) {
        return new IndexAdminClient.GenerationInfo(id, "qwen3-embedding:0.6b", 1024, status,
                10, 100, DB_NOW, DB_NOW);
    }
}
