package com.shitulelv.aicollab.agent.job;

import com.shitulelv.aicollab.internal.ai.index.IndexAdminClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * {@code REINDEX_GENERATION} 补偿推进器（P2 契约 §4.2、§12.7 R5-2）。
 *
 * <p>在补偿扫描的 Redisson 锁下运行：扫描 {@code job_type='REINDEX_GENERATION' AND status='SUCCEEDED'
 * AND index_phase='FANNED_OUT'} 的父任务，依据子任务数据库状态推进：
 * <ul>
 *   <li>任一子任务进入终态失败（FAILED / EXPIRED / CANCELED）→ 调 {@code :abandon} 并 CAS 置 {@code ABANDONED}；</li>
 *   <li>仍有在途子任务（QUEUED / LEASED / RUNNING / FAILED_RETRYABLE）→ 等待；</li>
 *   <li>全部子任务 {@code SUCCEEDED} → 分批 {@code :reconcile} 检查缺失；有缺失则补派子任务，
 *       无缺失则调 {@code :activate} 并 CAS 置 {@code ACTIVATED}。</li>
 * </ul></p>
 */
@Service
public class AiIndexReindexCompensator {

    private static final Logger log = LoggerFactory.getLogger(AiIndexReindexCompensator.class);

    private final AiJobRepository repository;
    private final AiIndexJobStore store;
    private final AiIndexJobService indexJobs;
    private final IndexAdminClient indexAdmin;
    private final Clock clock;

    @Autowired
    public AiIndexReindexCompensator(AiJobRepository repository, AiIndexJobStore store,
            AiIndexJobService indexJobs, IndexAdminClient indexAdmin) {
        this(repository, store, indexJobs, indexAdmin, Clock.systemUTC());
    }

    AiIndexReindexCompensator(AiJobRepository repository, AiIndexJobStore store,
            AiIndexJobService indexJobs, IndexAdminClient indexAdmin, Clock clock) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.store = Objects.requireNonNull(store, "store");
        this.indexJobs = Objects.requireNonNull(indexJobs, "indexJobs");
        this.indexAdmin = Objects.requireNonNull(indexAdmin, "indexAdmin");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public record AdvanceSummary(int activated, int abandoned, int reconciledDispatched) {
        public boolean idle() {
            return activated == 0 && abandoned == 0 && reconciledDispatched == 0;
        }
    }

    public AdvanceSummary advanceFannedOut(int limit) {
        int activated = 0;
        int abandoned = 0;
        int reconciledDispatched = 0;
        OffsetDateTime now = OffsetDateTime.now(clock);
        for (AiJobRecord parent : repository.findReindexFannedOut(limit)) {
            UUID generationId = parent.generationId();
            if (generationId == null) {
                log.warn("reindex parent missing generationId job_id={}", parent.id());
                continue;
            }
            try {
                Map<AiJobStatus, Integer> counts = repository.childStatusCounts(parent.id());
                int terminalFailures = count(counts, AiJobStatus.FAILED)
                        + count(counts, AiJobStatus.EXPIRED)
                        + count(counts, AiJobStatus.CANCELED);
                if (terminalFailures > 0) {
                    indexAdmin.abandon(generationId);
                    if (repository.casUpdateIndexPhase(parent.id(), parent.version(),
                            AiJobTypes.INDEX_PHASE_FANNED_OUT, AiJobTypes.INDEX_PHASE_ABANDONED, now).isPresent()) {
                        abandoned++;
                        log.info("reindex generation abandoned parent_job_id={} generation_id={} failed_children={}",
                                parent.id(), generationId, terminalFailures);
                    }
                    continue;
                }
                int inFlight = count(counts, AiJobStatus.QUEUED)
                        + count(counts, AiJobStatus.LEASED)
                        + count(counts, AiJobStatus.RUNNING)
                        + count(counts, AiJobStatus.FAILED_RETRYABLE);
                if (inFlight > 0) {
                    continue;
                }

                List<AiIndexJobStore.DocumentRef> missingDocs = findMissingReadyDocuments(generationId);
                if (!missingDocs.isEmpty()) {
                    indexJobs.enqueueReindexChildren(parent.id(), generationId, missingDocs);
                    reconciledDispatched += missingDocs.size();
                    log.info("reindex reconcile dispatched missing children parent_job_id={} generation_id={} count={}",
                            parent.id(), generationId, missingDocs.size());
                    continue;
                }

                indexAdmin.activate(generationId);
                if (repository.casUpdateIndexPhase(parent.id(), parent.version(),
                        AiJobTypes.INDEX_PHASE_FANNED_OUT, AiJobTypes.INDEX_PHASE_ACTIVATED, now).isPresent()) {
                    activated++;
                    log.info("reindex generation activated parent_job_id={} generation_id={}",
                            parent.id(), generationId);
                }
            } catch (IndexAdminClient.IndexAdminException failure) {
                log.warn("reindex compensation deferred parent_job_id={} generation_id={} kind={} status={}",
                        parent.id(), generationId, failure.kind(), failure.httpStatus());
            }
        }
        return new AdvanceSummary(activated, abandoned, reconciledDispatched);
    }

    private List<AiIndexJobStore.DocumentRef> findMissingReadyDocuments(UUID generationId) {
        List<AiIndexJobStore.DocumentRef> missing = new ArrayList<>();
        UUID afterId = null;
        while (true) {
            List<AiIndexJobStore.DocumentRef> batch = store.listReadyDocuments(
                    afterId, IndexAdminClient.MAX_BATCH_DOCUMENTS);
            if (batch.isEmpty()) {
                break;
            }
            Map<UUID, UUID> docToProject = new LinkedHashMap<>(batch.size());
            List<UUID> docIds = new ArrayList<>(batch.size());
            for (AiIndexJobStore.DocumentRef ref : batch) {
                docToProject.put(ref.documentId(), ref.projectId());
                docIds.add(ref.documentId());
            }
            for (UUID missingId : indexAdmin.reconcileMissing(generationId, docIds)) {
                UUID projectId = docToProject.get(missingId);
                if (projectId != null) {
                    missing.add(new AiIndexJobStore.DocumentRef(missingId, projectId));
                }
            }
            if (batch.size() < IndexAdminClient.MAX_BATCH_DOCUMENTS) {
                break;
            }
            afterId = batch.getLast().documentId();
        }
        return missing;
    }

    private static int count(Map<AiJobStatus, Integer> counts, AiJobStatus status) {
        return counts.getOrDefault(status, 0);
    }
}
