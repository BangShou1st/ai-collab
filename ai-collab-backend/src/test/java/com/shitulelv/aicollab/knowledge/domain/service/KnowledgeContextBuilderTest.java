package com.shitulelv.aicollab.knowledge.domain.service;

import com.shitulelv.aicollab.document.application.view.DocumentSearchHit;
import com.shitulelv.aicollab.knowledge.domain.model.KnowledgeSource;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 检索覆盖基线（D-RAG-01A）A 部分：来源数量边界。
 *
 * <p>这里断言的是<b>上下文构建器</b>的真实行为，不是模型回答质量：
 * “允许 12 个来源”不等于“回答正确”。本类全部为确定性单元测试，无数据库、无模型。</p>
 */
class KnowledgeContextBuilderTest {
    private static final int BUDGET_CODE_POINTS = 8000;

    private final KnowledgeContextBuilder builder = new KnowledgeContextBuilder();

    private static DocumentSearchHit hit(String content, String hash, double similarity) {
        return hit(UUID.randomUUID(), content, hash, similarity);
    }

    private static DocumentSearchHit hit(UUID documentId, String content, String hash, double similarity) {
        return new DocumentSearchHit(
                UUID.randomUUID(), documentId, "文档.pdf", "标题",
                content, hash, similarity, Map.of("pageNumber", 1));
    }

    /** n 个短而互不相同的有效来源（内容与 hash 都不同）。 */
    private static List<DocumentSearchHit> distinctHits(int n) {
        List<DocumentSearchHit> hits = new ArrayList<>(n);
        for (int i = 1; i <= n; i++) {
            hits.add(hit("第" + i + "条互不相同的短证据", "hash-" + i, 1.0 - i * 0.01));
        }
        return hits;
    }

    @Test
    void twelveShortDistinctSourcesAreAllRetainedWhenCallerAsksForTwelve() {
        var context = builder.build(distinctHits(12), 12);

        assertThat(context.sources()).hasSize(12);
        assertThat(context.sources()).extracting(source -> source.rank())
                .containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12);
        assertThat(context.promptSources()).contains("[S1]").contains("[S12]");
    }

    @Test
    void callerLimitBoundsRetainedSources() {
        assertThat(builder.build(distinctHits(12), 3).sources()).hasSize(3);
        assertThat(builder.build(distinctHits(12), 1).sources()).hasSize(1);
        assertThat(builder.build(distinctHits(12), 8).sources()).hasSize(8);
    }

    @Test
    void knowledgeQaDefaultStaysAtFiveSources() {
        var context = builder.build(distinctHits(12));

        assertThat(context.sources()).hasSize(KnowledgeContextBuilder.DEFAULT_MAX_SOURCES);
        assertThat(context.promptSources()).contains("[S5]").doesNotContain("[S6]");
    }

    @Test
    void bodyBudgetIsStillEnforcedWhenManySourcesAreAllowed() {
        // 每个来源正文约 3000 字符：12 个远超 8000 字符预算，必须按预算截断/停止追加。
        List<DocumentSearchHit> hits = new ArrayList<>();
        for (int i = 1; i <= 12; i++) {
            hits.add(hit("证".repeat(3000), "long-" + i, 1.0 - i * 0.01));
        }

        var context = builder.build(hits, 12);

        assertThat(context.sources()).isNotEmpty();
        assertThat(context.sources()).hasSizeLessThan(12);
        assertThat(context.promptSources().codePointCount(0, context.promptSources().length()))
                .isLessThanOrEqualTo(BUDGET_CODE_POINTS);
    }

    @Test
    void promptNeverExceedsBudgetEvenWhenEverySourceIsShort() {
        var context = builder.build(distinctHits(12), 12);

        assertThat(context.promptSources().codePointCount(0, context.promptSources().length()))
                .isLessThanOrEqualTo(BUDGET_CODE_POINTS);
    }

    @Test
    void duplicateContentHashWithinSameDocumentIsCountedOnce() {
        UUID doc = UUID.randomUUID();
        List<DocumentSearchHit> hits = List.of(
                hit(doc, "同一条证据", "same-hash", 0.9),
                hit(doc, "同一条证据（同文档分块副本）", "same-hash", 0.8),
                hit(doc, "另一条证据", "other-hash", 0.7));

        var context = builder.build(hits, 12);

        assertThat(context.sources()).hasSize(2);
        assertThat(context.sources()).extracting(source -> source.contentHash())
                .containsExactly("same-hash", "other-hash");
        assertThat(context.promptSources()).contains("[S1]").contains("[S2]").doesNotContain("[S3]");
    }

    @Test
    void identicalContentAcrossDocumentsKeepsDistinctSourcesInDefaultEntry() {
        UUID docA = UUID.randomUUID();
        UUID docB = UUID.randomUUID();
        List<DocumentSearchHit> hits = List.of(
                hit(docA, "同一条证据", "same-hash", 0.9),
                hit(docB, "同一条证据（不同文档副本）", "same-hash", 0.8),
                hit(docA, "另一条证据", "other-hash", 0.7));

        var context = builder.build(hits, 12);

        // 跨文档相同内容在默认入口也保留独立来源身份，不被全局 contentHash 吞掉
        assertThat(context.sources()).hasSize(3);
        assertThat(context.sources()).extracting(KnowledgeSource::contentHash)
                .containsExactly("same-hash", "same-hash", "other-hash");
        assertThat(context.promptSources()).contains("[S1]").contains("[S2]").contains("[S3]");
    }

    @Test
    void duplicatesDoNotConsumeTheRequestedSourceSlots() {
        // 前 12 条两两重复（每对属于同一文档的重复分块）：去重后只有 6 个真实来源，limit=12 时应保留 6 个而不是空出名额。
        List<DocumentSearchHit> hits = new ArrayList<>();
        for (int i = 1; i <= 6; i++) {
            UUID doc = UUID.randomUUID();
            hits.add(hit(doc, "证据" + i, "dup-" + i, 0.9));
            hits.add(hit(doc, "证据" + i + "（副本）", "dup-" + i, 0.8));
        }

        var context = builder.build(hits, 12);

        assertThat(context.sources()).hasSize(6);
    }

    @Test
    void belowThresholdAndBlankContentAreStillRejected() {
        List<DocumentSearchHit> hits = List.of(
                hit("相似度过低的证据", "low", KnowledgeContextBuilder.MIN_SIMILARITY - 0.01),
                hit("   ", "blank", 0.9),
                hit("有效证据", "ok", 0.9));

        var context = builder.build(hits, 12);

        assertThat(context.sources()).hasSize(1);
        assertThat(context.sources().getFirst().contentHash()).isEqualTo("ok");
    }

    @Test
    void metadataAndIdentitySurviveForEveryRetainedSource() {
        UUID chunk = UUID.randomUUID();
        UUID document = UUID.randomUUID();
        var context = builder.build(List.of(new DocumentSearchHit(
                chunk, document, "需求.pdf", "验收", "有效证据", "hash", 0.91,
                Map.of("pageNumber", 7))), 12);

        assertThat(context.sources()).singleElement().satisfies(source -> {
            assertThat(source.chunkId()).isEqualTo(chunk);
            assertThat(source.documentId()).isEqualTo(document);
            assertThat(source.originalFilename()).isEqualTo("需求.pdf");
            assertThat(source.heading()).isEqualTo("验收");
            assertThat(source.pageNumber()).isEqualTo(7);
        });
    }

    @Test
    void maxSourcesOutsideTheDeclaredBoundIsRejected() {
        assertThatThrownBy(() -> builder.build(distinctHits(1), 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> builder.build(distinctHits(1), 13))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(builder.build(distinctHits(1), KnowledgeContextBuilder.MAX_SOURCES_LIMIT).sources())
                .isNotEmpty();
    }

    // ---- D-RAG-01B：跨文档入口 buildDiverse ----

    private static DocumentSearchHit documentedHit(
            UUID chunkId, UUID documentId, String filename, String content, String hash,
            double similarity) {
        return new DocumentSearchHit(
                chunkId, documentId, filename, "标题", content, hash, similarity, Map.of("pageNumber", 1));
    }

    @Test
    void diverseEntryKeepsCrossDocumentEvidenceWithDistinctIdentities() {
        UUID documentA = UUID.randomUUID();
        UUID documentB = UUID.randomUUID();
        UUID chunkA = UUID.randomUUID();
        UUID chunkB = UUID.randomUUID();
        List<DocumentSearchHit> hits = List.of(
                documentedHit(chunkA, documentA, "原始件.md", "两件文档相同的条款", "same-hash", 0.9),
                documentedHit(chunkB, documentB, "复印件.md", "两件文档相同的条款", "same-hash", 0.8));

        var context = builder.buildDiverse(hits, 12);

        // 不同文档即使内容与 contentHash 相同，也各自保留可追溯的来源身份，不合并成同一文档。
        assertThat(context.sources()).hasSize(2);
        assertThat(context.sources()).extracting(KnowledgeSource::chunkId)
                .containsExactly(chunkA, chunkB);
        assertThat(context.sources()).extracting(KnowledgeSource::documentId)
                .containsExactly(documentA, documentB);
        assertThat(context.promptSources()).contains("[S1]").contains("[S2]").doesNotContain("[S3]");
    }

    @Test
    void diverseEntryFoldsIdenticalContentWithinTheSameDocument() {
        UUID document = UUID.randomUUID();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        List<DocumentSearchHit> hits = List.of(
                documentedHit(first, document, "文档.pdf", "同一条证据", "same", 0.9),
                documentedHit(second, document, "文档.pdf", "同一条证据（同文档副本）", "same", 0.8),
                documentedHit(other, document, "文档.pdf", "另一条证据", "other", 0.7));

        var context = builder.buildDiverse(hits, 12);

        // 同一文档内相同内容属于真正的冗余，折叠为一条且不占用其他来源名额。
        assertThat(context.sources()).hasSize(2);
        assertThat(context.sources().getFirst().chunkId()).isEqualTo(first);
        assertThat(context.sources()).extracting(KnowledgeSource::contentHash)
                .containsExactly("same", "other");
    }

    @Test
    void diverseEntrySharesBodyBudgetSoOneHugeChunkCannotStarveShorterCrossDocumentEvidence() {
        UUID documentA = UUID.randomUUID();
        UUID documentB = UUID.randomUUID();
        UUID huge = UUID.randomUUID();
        UUID shortEvidence = UUID.randomUUID();
        List<DocumentSearchHit> hits = List.of(
                documentedHit(huge, documentA, "巨块.md", "证".repeat(9000), "huge", 0.99),
                documentedHit(shortEvidence, documentB, "短证.md", "另一文档的关键短证据", "short", 0.5));

        var context = builder.buildDiverse(hits, 8);

        // 公平分配后，超大分块无法吃光 8000 字符预算，其他文档的短证据仍能进入上下文。
        assertThat(context.sources()).extracting(KnowledgeSource::chunkId)
                .containsExactly(huge, shortEvidence);
        assertThat(context.sources()).extracting(KnowledgeSource::rank).containsExactly(1, 2);
        assertThat(context.promptSources().codePointCount(0, context.promptSources().length()))
                .isLessThanOrEqualTo(BUDGET_CODE_POINTS);
    }

    @Test
    void diverseEntryRanksAreContiguousAndMarkersMatchRetainedSources() {
        UUID documentA = UUID.randomUUID();
        UUID documentB = UUID.randomUUID();
        UUID documentC = UUID.randomUUID();
        List<DocumentSearchHit> hits = List.of(
                documentedHit(UUID.randomUUID(), documentA, "甲.md", "甲的证据", "ha", 0.9),
                documentedHit(UUID.randomUUID(), documentB, "乙.md", "乙的证据", "hb", 0.8),
                documentedHit(UUID.randomUUID(), documentC, "丙.md", "丙的证据", "hc", 0.7),
                documentedHit(UUID.randomUUID(), documentA, "甲.md", "甲的第二条证据", "ha2", 0.5));

        var context = builder.buildDiverse(hits, 3);

        assertThat(context.sources()).hasSize(3);
        assertThat(context.sources()).extracting(KnowledgeSource::rank).containsExactly(1, 2, 3);
        assertThat(context.promptSources()).contains("[S1]").contains("[S2]").contains("[S3]")
                .doesNotContain("[S4]");
        // 三条来源来自三个不同文档，文档覆盖不因单一文档的高分而丢失。
        assertThat(context.sources()).extracting(KnowledgeSource::documentId)
                .containsExactly(documentA, documentB, documentC);
    }

    @Test
    void diverseEntryPromptNeverExceedsBudgetEvenWhenEverySourceIsLong() {
        List<DocumentSearchHit> hits = new ArrayList<>();
        for (int i = 1; i <= 12; i++) {
            hits.add(documentedHit(UUID.randomUUID(), UUID.randomUUID(),
                    "文档" + i + ".md", "证".repeat(3000), "long-" + i, 1.0 - i * 0.01));
        }

        var context = builder.buildDiverse(hits, 12);

        assertThat(context.promptSources().codePointCount(0, context.promptSources().length()))
                .isLessThanOrEqualTo(BUDGET_CODE_POINTS);
        assertThat(context.sources()).isNotEmpty();
    }

    @Test
    void diverseEntryRejectsMaxSourcesOutsideDeclaredBound() {
        assertThatThrownBy(() -> builder.buildDiverse(distinctHits(1), 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> builder.buildDiverse(distinctHits(1), 13))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void defaultEntryDoesNotReserveHashForCandidateThatCouldNotBeWritten() {
        UUID firstChunk = UUID.randomUUID();
        UUID failedChunk = UUID.randomUUID();
        UUID laterChunk = UUID.randomUUID();
        List<DocumentSearchHit> hits = List.of(
                documentedHit(firstChunk, UUID.randomUUID(), "文档.pdf", "x".repeat(7853), "first", 0.9),
                documentedHit(failedChunk, UUID.randomUUID(), "x".repeat(100), "&", "shared", 0.8),
                documentedHit(laterChunk, UUID.randomUUID(), "短.md", "第三条可写入", "shared", 0.7));

        var context = builder.build(hits, 12);

        // 第二条来源前缀较长，剩余正文预算不足以容纳转义后的 &amp;；
        // 它没有进入上下文，所以第三条同 hash、短前缀的来源仍应按 main 旧逻辑进入。
        assertThat(context.sources()).extracting(KnowledgeSource::chunkId)
                .containsExactly(firstChunk, laterChunk);
        assertThat(context.sources()).extracting(KnowledgeSource::rank).containsExactly(1, 2);
        assertThat(context.sources()).extracting(KnowledgeSource::originalFilename)
                .containsExactly("文档.pdf", "短.md");
        assertThat(context.promptSources()).contains("[S1]", "[S2]", "第三条可写入")
                .doesNotContain("[S3]");
        assertThat(context.promptSources().codePointCount(0, context.promptSources().length()))
                .isLessThanOrEqualTo(BUDGET_CODE_POINTS);
    }

    @Test
    void defaultEntryKeepsXmlEscapingInsideTheEightThousandCodePointLimit() {
        UUID chunk = UUID.randomUUID();
        var context = builder.build(List.of(documentedHit(
                chunk, UUID.randomUUID(), "文档&<>.md", "&<>😀".repeat(3000), "escaped", 0.9)), 12);

        assertThat(context.promptSources().codePointCount(0, context.promptSources().length()))
                .isLessThanOrEqualTo(BUDGET_CODE_POINTS);
        assertThat(context.promptSources()).contains("&amp;", "&lt;", "&gt;")
                .doesNotContain("&am;");
        assertThat(context.sources()).singleElement().satisfies(source -> {
            assertThat(source.chunkId()).isEqualTo(chunk);
            assertThat(source.rank()).isEqualTo(1);
        });
    }
}
