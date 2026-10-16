package com.shitulelv.aicollab.knowledge.domain.service;

import com.shitulelv.aicollab.document.application.view.DocumentSearchHit;
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
        return new DocumentSearchHit(
                UUID.randomUUID(), UUID.randomUUID(), "文档.pdf", "标题",
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
    void duplicateContentHashIsCountedOnce() {
        List<DocumentSearchHit> hits = List.of(
                hit("同一条证据", "same-hash", 0.9),
                hit("同一条证据（不同文档副本）", "same-hash", 0.8),
                hit("另一条证据", "other-hash", 0.7));

        var context = builder.build(hits, 12);

        assertThat(context.sources()).hasSize(2);
        assertThat(context.sources()).extracting(source -> source.contentHash())
                .containsExactly("same-hash", "other-hash");
        assertThat(context.promptSources()).contains("[S1]").contains("[S2]").doesNotContain("[S3]");
    }

    @Test
    void duplicatesDoNotConsumeTheRequestedSourceSlots() {
        // 前 12 条两两重复：去重后只有 6 个真实来源，limit=12 时应保留 6 个而不是空出名额。
        List<DocumentSearchHit> hits = new ArrayList<>();
        for (int i = 1; i <= 6; i++) {
            hits.add(hit("证据" + i, "dup-" + i, 0.9));
            hits.add(hit("证据" + i + "（副本）", "dup-" + i, 0.8));
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
}
