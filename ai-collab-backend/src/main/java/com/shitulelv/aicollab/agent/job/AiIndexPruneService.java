package com.shitulelv.aicollab.agent.job;

import com.shitulelv.aicollab.internal.ai.index.IndexAdminClient;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 孤儿索引行定期 {@code PRUNE} 兜底服务（P2 契约 §12.6 R4-2/R4-3）。
 *
 * <p>逻辑固定：
 * <ol>
 *   <li>经 {@link IndexAdminClient#listGenerations()} 找出全部 {@code ACTIVE} 与 {@code BUILDING} generation；</li>
 *   <li>在单个 {@code REPEATABLE READ} 事务中先取同一 PG 实例服务器时间 {@code SELECT now()} 作为 {@code asOf}，
 *       再按 UUID 升序分批 ≤ 5000 下发 {@code status IN ('UPLOADED','PARSING','INDEXING','READY')}；</li>
 *   <li>在事务外逐 generation 逐批调用 {@code :reconcile}（{@code mode='PRUNE'}），并累计 Micrometer 指标
 *       {@code ai_index.orphan_rows_deleted}（Prometheus: {@code ai_index_orphan_rows_deleted_total}）。</li>
 * </ol></p>
 */
@Service
public class AiIndexPruneService {

    public static final String METRIC_ORPHAN_ROWS_DELETED = "ai_index.orphan_rows_deleted";
    private static final Set<String> TARGET_STATUSES = Set.of("ACTIVE", "BUILDING");
    private static final Logger log = LoggerFactory.getLogger(AiIndexPruneService.class);

    private final AiIndexJobStore store;
    private final IndexAdminClient indexAdmin;
    private final MeterRegistry meterRegistry;

    public AiIndexPruneService(AiIndexJobStore store, IndexAdminClient indexAdmin,
            MeterRegistry meterRegistry) {
        this.store = Objects.requireNonNull(store, "store");
        this.indexAdmin = Objects.requireNonNull(indexAdmin, "indexAdmin");
        this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry");
    }

    public record PruneSummary(int generationsScanned, long deletedDocuments, long deletedRows) {
    }

    public PruneSummary pruneAll() {
        List<IndexAdminClient.GenerationInfo> targets = indexAdmin.listGenerations().stream()
                .filter(gen -> TARGET_STATUSES.contains(gen.status()))
                .toList();
        if (targets.isEmpty()) {
            return new PruneSummary(0, 0, 0);
        }

        AiIndexJobStore.PruneSnapshot snapshot = store.snapshotIndexableForPrune();
        int scanned = 0;
        long totalDeletedDocuments = 0;
        long totalDeletedRows = 0;

        for (IndexAdminClient.GenerationInfo gen : targets) {
            try {
                long genDocs = 0;
                long genRows = 0;
                for (AiIndexJobStore.PruneBatch batch : snapshot.batches()) {
                    IndexAdminClient.PruneResult res = indexAdmin.reconcilePrune(
                            gen.id(), snapshot.asOf(), batch.range(), batch.documentIds());
                    genDocs += res.deletedDocuments();
                    genRows += res.deletedRows();
                }
                scanned++;
                totalDeletedDocuments += genDocs;
                totalDeletedRows += genRows;
                recordDeletedRows(genRows);
                if (genRows > 0 || genDocs > 0) {
                    log.info("ai index prune completed generation_id={} status={} deleted_documents={} deleted_rows={}",
                            gen.id(), gen.status(), genDocs, genRows);
                }
            } catch (IndexAdminClient.IndexAdminException failure) {
                log.warn("ai index prune failed generation_id={} status={} kind={} http_status={}",
                        gen.id(), gen.status(), failure.kind(), failure.httpStatus());
            }
        }
        return new PruneSummary(scanned, totalDeletedDocuments, totalDeletedRows);
    }

    private void recordDeletedRows(long deletedRows) {
        try {
            meterRegistry.counter(METRIC_ORPHAN_ROWS_DELETED).increment(deletedRows);
        } catch (RuntimeException ignored) {
            // 指标上报异常不影响清理结果。
        }
    }
}
