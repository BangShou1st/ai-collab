package com.shitulelv.aicollab.retrieval.remote;

import static org.assertj.core.api.Assertions.assertThat;

import com.shitulelv.aicollab.retrieval.remote.KnowledgeHitVerifier.BodyRow;
import com.shitulelv.aicollab.retrieval.remote.KnowledgeHitVerifier.RejectReason;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 契约 §6 与 §9.2 第 3、4 条：Python 返回的 hit 越权或身份不符时，Java 全部丢弃并计数。 */
class KnowledgeHitVerifierTest {

    private static final UUID PROJECT = UUID.randomUUID();
    private static final UUID DOC_A = UUID.randomUUID();
    private static final UUID DOC_B = UUID.randomUUID();
    private static final UUID SNAP_A = UUID.randomUUID();
    private static final UUID SNAP_B = UUID.randomUUID();
    private static final String H3 = "a".repeat(64);
    private static final String H12 = "b".repeat(64);
    private static final String HB = "c".repeat(64);

    private FakeRepo repo;
    private SimpleMeterRegistry registry;
    private KnowledgeHitVerifier verifier;

    @BeforeEach
    void setUp() {
        repo = new FakeRepo();
        repo.snapshots.put(DOC_A, SNAP_A);
        repo.snapshots.put(DOC_B, SNAP_B);
        repo.rows.add(new BodyRow(DOC_A, 3, "父章节", "父块正文（数据库）", H3));
        repo.rows.add(new BodyRow(DOC_A, 12, "章节", "子块正文（数据库）", H12));
        repo.rows.add(new BodyRow(DOC_B, 1, "B", "B 文档正文", HB));
        registry = new SimpleMeterRegistry();
        verifier = new KnowledgeHitVerifier(repo, registry);
    }

    @Test
    void validHitIsAcceptedWithDatabaseContentNotPythonText() {
        var outcome = verifier.verify(PROJECT, List.of(DOC_A),
                List.of(hit(DOC_A, 12, H12, SNAP_A, context(ref(3, H3), ref(12, H12)))));

        assertThat(outcome.accepted()).hasSize(1);
        assertThat(outcome.rejected()).isEmpty();
        assertThat(outcome.insufficientEvidence()).isFalse();
        var verified = outcome.accepted().getFirst();
        assertThat(verified.content()).isEqualTo("子块正文（数据库）");
        assertThat(verified.heading()).isEqualTo("章节");
        assertThat(verified.bodySnapshotId()).isEqualTo(SNAP_A);
        assertThat(verified.contextRows()).extracting(BodyRow::chunkNo).containsExactly(3, 12);
    }

    @Test
    void documentOutsideVisibleSetIsRejectedAndNeverQueried() {
        var outcome = verifier.verify(PROJECT, List.of(DOC_A),
                List.of(hit(DOC_B, 1, HB, SNAP_B, null)));

        assertThat(outcome.accepted()).isEmpty();
        assertThat(outcome.insufficientEvidence()).isTrue();
        assertThat(outcome.rejected()).containsExactly(Map.entry(RejectReason.NOT_VISIBLE, 1));
        assertThat(repo.queriedDocuments).doesNotContain(DOC_B);
        assertThat(counter(RejectReason.NOT_VISIBLE)).isEqualTo(1.0);
    }

    @Test
    void emptyVisibleSetRejectsEverything() {
        var outcome = verifier.verify(PROJECT, List.of(), List.of(hit(DOC_A, 12, H12, SNAP_A, null)));

        assertThat(outcome.accepted()).isEmpty();
        assertThat(outcome.rejected()).containsEntry(RejectReason.NOT_VISIBLE, 1);
        assertThat(repo.queriedDocuments).isEmpty();
    }

    @Test
    void staleSnapshotIsRejected() {
        var outcome = verifier.verify(PROJECT, List.of(DOC_A),
                List.of(hit(DOC_A, 12, H12, UUID.randomUUID(), null)));

        assertThat(outcome.accepted()).isEmpty();
        assertThat(outcome.rejected()).containsExactly(Map.entry(RejectReason.SNAPSHOT_STALE, 1));
    }

    @Test
    void missingChunkNoIsRejected() {
        var outcome = verifier.verify(PROJECT, List.of(DOC_A), List.of(hit(DOC_A, 99, H12, SNAP_A, null)));

        assertThat(outcome.rejected()).containsExactly(Map.entry(RejectReason.CHUNK_MISSING, 1));
    }

    @Test
    void contentHashMismatchIsRejected() {
        var outcome = verifier.verify(PROJECT, List.of(DOC_A),
                List.of(hit(DOC_A, 12, "f".repeat(64), SNAP_A, null)));

        assertThat(outcome.rejected()).containsExactly(Map.entry(RejectReason.HASH_MISMATCH, 1));
    }

    @Test
    void oneBadChunkRefDropsTheWholeHit() {
        var outcome = verifier.verify(PROJECT, List.of(DOC_A),
                List.of(hit(DOC_A, 12, H12, SNAP_A, context(ref(3, "0".repeat(64)), ref(12, H12)))));

        assertThat(outcome.accepted()).isEmpty();
        assertThat(outcome.rejected()).containsExactly(Map.entry(RejectReason.CHUNK_REF_MISMATCH, 1));
    }

    @Test
    void chunkRefPointingAtMissingRowDropsTheWholeHit() {
        var outcome = verifier.verify(PROJECT, List.of(DOC_A),
                List.of(hit(DOC_A, 12, H12, SNAP_A, context(ref(77, H3)))));

        assertThat(outcome.rejected()).containsExactly(Map.entry(RejectReason.CHUNK_REF_MISMATCH, 1));
    }

    @Test
    void documentRevokedBetweenSearchAndVerificationIsRejected() {
        // §9.2 第 4 条：检索完成后、复核前文档被 access_blocked，可见性查询不再返回它。
        repo.snapshots.remove(DOC_A);

        var outcome = verifier.verify(PROJECT, List.of(DOC_A), List.of(hit(DOC_A, 12, H12, SNAP_A, null)));

        assertThat(outcome.accepted()).isEmpty();
        assertThat(outcome.rejected()).containsExactly(Map.entry(RejectReason.DOCUMENT_UNAVAILABLE, 1));
    }

    @Test
    void malformedHitsAreRejected() {
        var outcome = verifier.verify(PROJECT, List.of(DOC_A), List.of(
                hit(DOC_A, null, H12, SNAP_A, null),
                hit(DOC_A, 12, " ", SNAP_A, null),
                hit(DOC_A, 12, H12, null, null),
                hit(DOC_A, 12, H12, SNAP_A, new KnowledgeSearchModels.Context("x", List.of()))));

        assertThat(outcome.accepted()).isEmpty();
        assertThat(outcome.rejected()).containsExactly(Map.entry(RejectReason.MALFORMED, 4));
    }

    @Test
    void mixedBatchKeepsOnlyValidHitsInOrderAndCountsEachReason() {
        var outcome = verifier.verify(PROJECT, List.of(DOC_A, DOC_B), List.of(
                hit(DOC_B, 1, HB, SNAP_B, null),
                hit(UUID.randomUUID(), 1, HB, SNAP_B, null),
                hit(DOC_A, 12, "f".repeat(64), SNAP_A, null),
                hit(DOC_A, 3, H3, SNAP_A, null)));

        assertThat(outcome.accepted()).extracting(KnowledgeHitVerifier.VerifiedHit::documentId)
                .containsExactly(DOC_B, DOC_A);
        assertThat(outcome.rejected()).containsOnly(
                Map.entry(RejectReason.NOT_VISIBLE, 1), Map.entry(RejectReason.HASH_MISMATCH, 1));
        assertThat(outcome.rejectedTotal()).isEqualTo(2);
    }

    @Test
    void noHitsMeansInsufficientEvidenceWithoutQueries() {
        var outcome = verifier.verify(PROJECT, List.of(DOC_A), List.of());

        assertThat(outcome.insufficientEvidence()).isTrue();
        assertThat(repo.queriedDocuments).isEmpty();
    }

    @Test
    void contentHashComparisonIsCaseInsensitive() {
        var outcome = verifier.verify(PROJECT, List.of(DOC_A), List.of(
                hit(DOC_A, 12, H12.toUpperCase(java.util.Locale.ROOT), SNAP_A,
                        context(ref(3, H3.toUpperCase(java.util.Locale.ROOT)), ref(12, H12)))));

        assertThat(outcome.accepted()).hasSize(1);
        assertThat(outcome.accepted().getFirst().contentHash()).isEqualTo(H12);
        assertThat(outcome.rejected()).isEmpty();
    }

    private double counter(RejectReason reason) {
        var counter = registry.find(KnowledgeHitVerifier.METRIC_REJECTED).tag("reason", reason.name()).counter();
        return counter == null ? 0.0 : counter.count();
    }

    private static KnowledgeSearchModels.Hit hit(UUID doc, Integer chunkNo, String hash, UUID snapshot,
            KnowledgeSearchModels.Context context) {
        return new KnowledgeSearchModels.Hit(1, doc, chunkNo, hash, "CHILD", null, "h",
                "Python 返回的文本，只用于比对", snapshot, Map.of(), context,
                new KnowledgeSearchModels.Scores(0.8, null, 0.01, null, 0.8));
    }

    private static KnowledgeSearchModels.Context context(KnowledgeSearchModels.ChunkRef... refs) {
        return new KnowledgeSearchModels.Context("扩展文本", List.of(refs));
    }

    private static KnowledgeSearchModels.ChunkRef ref(int chunkNo, String hash) {
        return new KnowledgeSearchModels.ChunkRef(chunkNo, hash);
    }

    private static final class FakeRepo implements KnowledgeHitVerifier.HitVerificationStore {
        final Map<UUID, UUID> snapshots = new HashMap<>();
        final List<BodyRow> rows = new ArrayList<>();
        final Set<UUID> queriedDocuments = new HashSet<>();

        @Override
        public Map<UUID, UUID> readableSnapshots(UUID projectId, Collection<UUID> documentIds) {
            queriedDocuments.addAll(documentIds);
            Map<UUID, UUID> result = new HashMap<>();
            for (UUID id : documentIds) {
                if (snapshots.containsKey(id)) {
                    result.put(id, snapshots.get(id));
                }
            }
            return result;
        }

        @Override
        public List<BodyRow> bodyRows(Collection<UUID> documentIds, Collection<Integer> chunkNos) {
            queriedDocuments.addAll(documentIds);
            return rows.stream()
                    .filter(r -> documentIds.contains(r.documentId()) && chunkNos.contains(r.chunkNo()))
                    .toList();
        }
    }
}
