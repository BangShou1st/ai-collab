package com.shitulelv.aicollab.knowledge.domain.service;

import com.shitulelv.aicollab.document.application.view.DocumentSearchHit;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D-RAG-01B：跨文档来源选择策略的确定性单测（纯函数，无数据库、无模型）。
 *
 * <p>断言的是<b>选择顺序与去重身份</b>，不是模型回答质量：相同输入必须得到相同输出。</p>
 */
class CrossDocumentSourceSelectorTest {
    private final CrossDocumentSourceSelector selector = new CrossDocumentSourceSelector();

    private static DocumentSearchHit hit(UUID chunkId, UUID documentId, String content,
            String hash, double similarity) {
        return new DocumentSearchHit(
                chunkId, documentId, "文档.pdf", "标题", content, hash, similarity, Map.of());
    }

    /** 生成按确定性 chunkId 递增的 id，便于对平分相似度断言稳定次序。 */
    private static UUID orderedId(int index) {
        return new UUID(0, index);
    }

    @Test
    void diversityKeepsEvidenceFromDocumentsThatOneHighScoringDocumentWouldCrowdOut() {
        // 文档 A 有 6 条高分候选，文档 B 只有 1 条中等相关证据。
        UUID documentA = UUID.randomUUID();
        UUID documentB = UUID.randomUUID();
        List<DocumentSearchHit> hits = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            hits.add(hit(orderedId(i + 1), documentA, "A 的证据 " + i, "hash-a-" + i, 0.95 - i * 0.01));
        }
        UUID evidenceB = orderedId(100);
        hits.add(hit(evidenceB, documentB, "B 的关键证据", "hash-b", 0.5));

        var selected = selector.select(hits, 3);

        assertThat(selected).hasSize(3);
        // 单一高分文档不能把候选窗口占满：文档 B 的有效来源必须进得来。
        assertThat(selected).extracting(DocumentSearchHit::documentId).contains(documentB);
        assertThat(selected).extracting(DocumentSearchHit::id).contains(evidenceB);
        // 轮转次序：文档 A（最佳 0.95）先拿一个名额，文档 B 再拿一个，剩余名额按相似度补文档 A。
        assertThat(selected).extracting(DocumentSearchHit::documentId)
                .containsExactly(documentA, documentB, documentA);
        assertThat(selected.get(0).similarity()).isGreaterThan(selected.get(2).similarity());
    }

    @Test
    void secondPhaseFillsByGlobalRelevanceInsteadOfRoundRobin() {
        UUID documentA = UUID.randomUUID();
        UUID documentB = UUID.randomUUID();
        List<DocumentSearchHit> hits = List.of(
                hit(orderedId(1), documentA, "A1", "a1", 0.99),
                hit(orderedId(2), documentA, "A2", "a2", 0.98),
                hit(orderedId(3), documentA, "A3", "a3", 0.97),
                hit(orderedId(4), documentB, "B1", "b1", 0.96),
                hit(orderedId(5), documentB, "B2", "b2", 0.20));

        var selected = selector.select(hits, 4);

        assertThat(selected).extracting(DocumentSearchHit::id)
                .containsExactly(orderedId(1), orderedId(4), orderedId(2), orderedId(3));
        assertThat(selected).extracting(DocumentSearchHit::id).doesNotContain(orderedId(5));
    }

    @Test
    void diversityMayKeepAWeakButEligibleDocumentRepresentative() {
        UUID documentA = UUID.randomUUID();
        UUID documentB = UUID.randomUUID();
        List<DocumentSearchHit> hits = List.of(
                hit(orderedId(1), documentA, "强相关 A1", "a1", 0.99),
                hit(orderedId(2), documentA, "相关 A2", "a2", 0.98),
                hit(orderedId(3), documentB, "弱相关 B1", "b1", 0.16));

        var selected = selector.select(hits, 2);

        // 多样性保底可能牺牲全局相关性；0.16 仍高于既有 0.15 阈值，本轮不引入新阈值。
        assertThat(selected).extracting(DocumentSearchHit::id)
                .containsExactly(orderedId(1), orderedId(3));
        assertThat(selected.getLast().similarity()).isEqualTo(0.16);
    }

    @Test
    void crossDocumentIdenticalContentKeepsBothSourceIdentities() {
        UUID documentA = UUID.randomUUID();
        UUID documentB = UUID.randomUUID();
        UUID chunkA = orderedId(1);
        UUID chunkB = orderedId(2);
        List<DocumentSearchHit> hits = List.of(
                hit(chunkA, documentA, "两件文档里完全相同的条款文本", "same-hash", 0.9),
                hit(chunkB, documentB, "两件文档里完全相同的条款文本", "same-hash", 0.8));

        var selected = selector.select(hits, 12);

        // contentHash 相同但文档不同：两份引用身份都要保留，不能合并成一条。
        assertThat(selected).hasSize(2);
        assertThat(selected).extracting(DocumentSearchHit::id).containsExactly(chunkA, chunkB);
        assertThat(selected).extracting(DocumentSearchHit::documentId)
                .containsExactly(documentA, documentB);
        assertThat(selected).extracting(DocumentSearchHit::contentHash)
                .containsExactly("same-hash", "same-hash");
    }

    @Test
    void identicalContentWithinTheSameDocumentIsFoldedOnce() {
        UUID document = UUID.randomUUID();
        List<DocumentSearchHit> hits = List.of(
                hit(orderedId(1), document, "同一条证据", "dup", 0.9),
                hit(orderedId(2), document, "同一条证据（同文档副本）", "dup", 0.8),
                hit(orderedId(3), document, "另一条证据", "other", 0.7));

        var selected = selector.select(hits, 12);

        // 同一文档内相同 contentHash 是真正的冗余，折叠为一条且不占用其他名额。
        assertThat(selected).hasSize(2);
        assertThat(selected).extracting(DocumentSearchHit::contentHash)
                .containsExactly("dup", "other");
        assertThat(selected).extracting(DocumentSearchHit::id)
                .containsExactly(orderedId(1), orderedId(3));
    }

    @Test
    void singleDocumentSelectionFallsBackToSimilarityOrder() {
        UUID document = UUID.randomUUID();
        List<DocumentSearchHit> hits = List.of(
                hit(orderedId(1), document, "低分", "h1", 0.5),
                hit(orderedId(2), document, "高分", "h2", 0.9),
                hit(orderedId(3), document, "中分", "h3", 0.7));

        var selected = selector.select(hits, 12);

        // 单文档时“跨文档优先”退化为原有相似度顺序，行为不变。
        assertThat(selected).extracting(DocumentSearchHit::similarity)
                .containsExactly(0.9, 0.7, 0.5);
    }

    @Test
    void ineligibleCandidatesAreRemovedBeforeDiversity() {
        UUID documentA = UUID.randomUUID();
        UUID documentB = UUID.randomUUID();
        List<DocumentSearchHit> hits = List.of(
                hit(orderedId(1), documentA, "相似度不合格", "low",
                        KnowledgeContextBuilder.MIN_SIMILARITY - 0.01),
                hit(orderedId(2), documentA, "   ", "blank", 0.9),
                hit(orderedId(3), documentB, "有效证据", "ok", 0.5));

        var selected = selector.select(hits, 12);

        // 不合格候选不会因为“它属于另一个文档”而被当成有效来源留下。
        assertThat(selected).singleElement()
                .satisfies(hit -> assertThat(hit.id()).isEqualTo(orderedId(3)));
    }

    @Test
    void tiedSimilarityFallsBackToStableChunkIdentity() {
        UUID document = UUID.randomUUID();
        List<DocumentSearchHit> hits = List.of(
                hit(orderedId(3), document, "证据三", "h3", 0.8),
                hit(orderedId(1), document, "证据一", "h1", 0.8),
                hit(orderedId(2), document, "证据二", "h2", 0.8));

        assertThat(selector.select(hits, 12)).extracting(DocumentSearchHit::id)
                .containsExactly(orderedId(1), orderedId(2), orderedId(3));
    }

    @Test
    void documentsAreOrderedByTheirBestCandidateNotByFirstSeenChunk() {
        UUID documentA = UUID.randomUUID();
        UUID documentB = UUID.randomUUID();
        // 输入故意把文档 A 的弱候选放在最前，文档 B 的强候选放在后面。
        List<DocumentSearchHit> hits = List.of(
                hit(orderedId(1), documentA, "A 弱", "ha", 0.4),
                hit(orderedId(2), documentB, "B 强", "hb", 0.99));

        assertThat(selector.select(hits, 12)).extracting(DocumentSearchHit::documentId)
                .containsExactly(documentB, documentA);
    }

    @Test
    void selectionIsDeterministicAcrossRepeatedRuns() {
        UUID documentA = UUID.randomUUID();
        UUID documentB = UUID.randomUUID();
        List<DocumentSearchHit> hits = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            hits.add(hit(orderedId(i + 1), documentA, "A" + i, "ha" + i, 0.9 - i * 0.01));
        }
        hits.add(hit(orderedId(50), documentB, "B 的证据", "hb", 0.4));

        var first = selector.select(hits, 5);
        var second = selector.select(hits, 5);

        assertThat(first).extracting(DocumentSearchHit::id)
                .containsExactlyElementsOf(second.stream().map(DocumentSearchHit::id).toList());
        assertThat(first).hasSize(5);
    }

    @Test
    void selectionNeverExceedsRequestedSourceCount() {
        List<DocumentSearchHit> hits = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            hits.add(hit(orderedId(i), UUID.randomUUID(), "证据" + i, "h" + i, 0.9 - i * 0.01));
        }

        assertThat(selector.select(hits, 2)).hasSize(2);
        assertThat(selector.select(hits, 1)).hasSize(1);
        assertThat(selector.select(hits, 0)).isEmpty();
        assertThat(selector.select(List.of(), 5)).isEmpty();
    }
}
