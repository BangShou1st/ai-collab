package com.shitulelv.aicollab.retrieval.remote;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * P2 契约 §6：对 Python {@code POST /v1/knowledge/search} 返回的每条 hit 做身份复核。
 *
 * <p>按顺序校验，任意一条不满足即丢弃并计入 {@code knowledge_citation_rejected_total{reason}}：
 * <ol>
 *   <li>{@code documentId ∈ visibleDocumentIds}（本次请求下发的集合）；</li>
 *   <li>文档当前仍 READY、未封锁、可读（复核时重新查询，覆盖检索期间撤权的竞态）；</li>
 *   <li>{@code bodySnapshotId == document_body.snapshot_id}；</li>
 *   <li>{@code document_body_chunk} 存在 {@code (document_id, chunk_no)} 且 {@code content_hash} 相等；</li>
 *   <li>{@code context} 非空时 {@code chunkRefs} 每一行按第 4 条复核，任一不通过整条丢弃；</li>
 *   <li>全部丢弃时视为证据不足。</li>
 * </ol>
 * 正文以 Java 从 {@code document_body_chunk.content} 读到的内容为准，Python 返回的文本不进入结果。
 * 集合外的 documentId 不会被带进任何数据库查询。日志与指标不含查询原文、正文与令牌。
 */
@Component
public class KnowledgeHitVerifier {

    /** Micrometer 名称；Prometheus 导出为 {@code knowledge_citation_rejected_total}。 */
    public static final String METRIC_REJECTED = "knowledge.citation.rejected";

    public enum RejectReason {
        MALFORMED,
        NOT_VISIBLE,
        DOCUMENT_UNAVAILABLE,
        SNAPSHOT_STALE,
        CHUNK_MISSING,
        HASH_MISMATCH,
        CHUNK_REF_MISMATCH
    }

    /** 复核依赖的只读数据访问；生产实现见 {@link JdbcKnowledgeHitRepository}。 */
    public interface Repository {
        /** 返回其中当前 READY、未封锁、可读的文档 → 当前 {@code document_body.snapshot_id}（无正文的文档不出现）。 */
        Map<UUID, UUID> readableSnapshots(UUID projectId, Collection<UUID> documentIds);

        /** 返回这些文档、这些 chunk_no 下的正文行；调用方自行按 (documentId, chunkNo) 过滤。 */
        List<BodyRow> bodyRows(Collection<UUID> documentIds, Collection<Integer> chunkNos);
    }

    public record BodyRow(UUID documentId, int chunkNo, String heading, String content, String contentHash) {
    }

    /** 复核通过的 hit。{@code content}/{@code heading} 取自数据库；{@code contextRows} 为 chunkRefs 对应的数据库行。 */
    public record VerifiedHit(
            KnowledgeSearchModels.Hit hit,
            UUID documentId,
            int chunkNo,
            String contentHash,
            UUID bodySnapshotId,
            String heading,
            String content,
            List<BodyRow> contextRows) {
    }

    public record Outcome(List<VerifiedHit> accepted, Map<RejectReason, Integer> rejected) {
        /** 契约 §6 第 6 条：全部被丢弃（或本来就没有 hit）时按"证据不足"处理。 */
        public boolean insufficientEvidence() {
            return accepted.isEmpty();
        }

        public int rejectedTotal() {
            return rejected.values().stream().mapToInt(Integer::intValue).sum();
        }
    }

    private record Key(UUID documentId, int chunkNo) {
    }

    private final Repository repository;
    private final MeterRegistry registry;

    public KnowledgeHitVerifier(Repository repository, MeterRegistry registry) {
        this.repository = Objects.requireNonNull(repository);
        this.registry = Objects.requireNonNull(registry);
    }

    public Outcome verify(UUID projectId, Collection<UUID> visibleDocumentIds, List<KnowledgeSearchModels.Hit> hits) {
        Objects.requireNonNull(projectId, "projectId");
        Map<RejectReason, Integer> rejected = new EnumMap<>(RejectReason.class);
        if (hits == null || hits.isEmpty()) {
            return new Outcome(List.of(), Map.copyOf(rejected));
        }
        Set<UUID> visible = visibleDocumentIds == null ? Set.of() : Set.copyOf(visibleDocumentIds);

        // 第 1 条先在内存里过：集合外的 documentId 不进入任何查询。
        List<KnowledgeSearchModels.Hit> inScope = new ArrayList<>(hits.size());
        Set<UUID> docs = new LinkedHashSet<>();
        Set<Integer> chunkNos = new LinkedHashSet<>();
        for (KnowledgeSearchModels.Hit hit : hits) {
            if (!wellFormed(hit)) {
                reject(rejected, RejectReason.MALFORMED);
                continue;
            }
            if (!visible.contains(hit.documentId())) {
                reject(rejected, RejectReason.NOT_VISIBLE);
                continue;
            }
            inScope.add(hit);
            docs.add(hit.documentId());
            chunkNos.add(hit.chunkNo());
            if (hit.context() != null) {
                for (KnowledgeSearchModels.ChunkRef ref : hit.context().chunkRefs()) {
                    chunkNos.add(ref.chunkNo());
                }
            }
        }
        if (inScope.isEmpty()) {
            return new Outcome(List.of(), Map.copyOf(rejected));
        }

        Map<UUID, UUID> snapshots = repository.readableSnapshots(projectId, List.copyOf(docs));
        Set<UUID> readableDocs = snapshots.keySet();
        Map<Key, BodyRow> rows = new java.util.HashMap<>();
        if (!readableDocs.isEmpty()) {
            for (BodyRow row : repository.bodyRows(List.copyOf(readableDocs), List.copyOf(chunkNos))) {
                if (row != null && readableDocs.contains(row.documentId())) {
                    rows.putIfAbsent(new Key(row.documentId(), row.chunkNo()), row);
                }
            }
        }

        List<VerifiedHit> accepted = new ArrayList<>(inScope.size());
        for (KnowledgeSearchModels.Hit hit : inScope) {
            UUID snapshot = snapshots.get(hit.documentId());
            if (snapshot == null) {
                reject(rejected, RejectReason.DOCUMENT_UNAVAILABLE);
                continue;
            }
            if (!snapshot.equals(hit.bodySnapshotId())) {
                reject(rejected, RejectReason.SNAPSHOT_STALE);
                continue;
            }
            BodyRow row = rows.get(new Key(hit.documentId(), hit.chunkNo()));
            if (row == null) {
                reject(rejected, RejectReason.CHUNK_MISSING);
                continue;
            }
            if (!sameHash(row.contentHash(), hit.contentHash())) {
                reject(rejected, RejectReason.HASH_MISMATCH);
                continue;
            }
            List<BodyRow> contextRows = List.of();
            if (hit.context() != null) {
                contextRows = verifyRefs(hit, rows);
                if (contextRows == null) {
                    reject(rejected, RejectReason.CHUNK_REF_MISMATCH);
                    continue;
                }
            }
            accepted.add(new VerifiedHit(hit, hit.documentId(), hit.chunkNo(), row.contentHash().trim(),
                    snapshot, row.heading(), row.content(), contextRows));
        }
        return new Outcome(List.copyOf(accepted), Map.copyOf(rejected));
    }

    private static List<BodyRow> verifyRefs(KnowledgeSearchModels.Hit hit, Map<Key, BodyRow> rows) {
        List<BodyRow> result = new ArrayList<>(hit.context().chunkRefs().size());
        for (KnowledgeSearchModels.ChunkRef ref : hit.context().chunkRefs()) {
            BodyRow row = rows.get(new Key(hit.documentId(), ref.chunkNo()));
            if (row == null || !sameHash(row.contentHash(), ref.contentHash())) {
                return null;
            }
            result.add(row);
        }
        return List.copyOf(result);
    }

    private static boolean wellFormed(KnowledgeSearchModels.Hit hit) {
        if (hit == null || hit.documentId() == null || hit.chunkNo() == null || hit.chunkNo() < 0
                || blank(hit.contentHash()) || hit.bodySnapshotId() == null) {
            return false;
        }
        if (hit.context() != null) {
            List<KnowledgeSearchModels.ChunkRef> refs = hit.context().chunkRefs();
            if (refs == null || refs.isEmpty()) {
                return false;
            }
            for (KnowledgeSearchModels.ChunkRef ref : refs) {
                if (ref == null || ref.chunkNo() == null || ref.chunkNo() < 0 || blank(ref.contentHash())) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean sameHash(String stored, String claimed) {
        return stored != null && claimed != null && stored.trim().equals(claimed.trim());
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private void reject(Map<RejectReason, Integer> rejected, RejectReason reason) {
        rejected.merge(reason, 1, Integer::sum);
        Counter.builder(METRIC_REJECTED)
                .description("Python 检索返回、Java 引用复核丢弃的 hit 数（契约 §6）")
                .tag("reason", reason.name())
                .register(registry)
                .increment();
    }
}
