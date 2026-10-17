package com.shitulelv.aicollab.knowledge.domain.service;

import com.shitulelv.aicollab.knowledge.domain.model.KnowledgeSource;
import com.shitulelv.aicollab.knowledge.domain.model.ValidatedKnowledgeAnswer;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeCitationValidatorTest {

    private final KnowledgeCitationValidator validator = new KnowledgeCitationValidator();

    private KnowledgeSource source(int rank) {
        return new KnowledgeSource(UUID.randomUUID(), UUID.randomUUID(), "doc.pdf",
                "标题", "内容", "hash", 0.9, rank);
    }

    @Test
    void keeps_valid_citations_and_drops_unknown_ones() {
        List<KnowledgeSource> sources = List.of(source(1), source(3));
        String answer = "结论 [S1] 与补充 [S9] 以及再次引用 [S1]。";

        ValidatedKnowledgeAnswer result = validator.validate(answer, sources);

        assertThat(result.insufficientEvidence()).isFalse();
        assertThat(result.answer()).isEqualTo("结论 [S1] 与补充  以及再次引用 [S1]。");
        assertThat(result.citedSources()).extracting(KnowledgeSource::rank)
                .containsExactly(1);
        assertThat(result.invalidCitationCount()).isEqualTo(1);
    }

    @Test
    void answer_without_citations_has_no_verified_evidence() {
        ValidatedKnowledgeAnswer result = validator.validate("这是没有引用的回答", List.of(source(2)));

        assertThat(result.insufficientEvidence()).isTrue();
        assertThat(result.answer()).isEqualTo(KnowledgeCitationValidator.INSUFFICIENT_ANSWER);
        assertThat(result.citedSources()).isEmpty();
    }

    @Test
    void exact_insufficient_answer_is_recognized() {
        ValidatedKnowledgeAnswer result =
                validator.validate(KnowledgeCitationValidator.INSUFFICIENT_ANSWER, List.of(source(1)));

        assertThat(result.insufficientEvidence()).isTrue();
        assertThat(result.invalidOutput()).isFalse();
        assertThat(result.citedSources()).isEmpty();
    }

    @Test void invalid_citations_with_substantive_prose_are_insufficient() {
        var result = validator.validate("已完成所有工作 [S99]", List.of(source(1)));
        assertThat(result.insufficientEvidence()).isTrue();
        assertThat(result.invalidCitationCount()).isEqualTo(1);
        assertThat(result.citedSources()).isEmpty();
    }

    @Test
    void blank_answer_is_invalid_output() {
        ValidatedKnowledgeAnswer result = validator.validate("   ", List.of(source(1)));

        assertThat(result.insufficientEvidence()).isTrue();
        assertThat(result.invalidOutput()).isTrue();
    }

    @Test
    void null_answer_is_invalid_output() {
        ValidatedKnowledgeAnswer result = validator.validate(null, List.of(source(1)));

        assertThat(result.insufficientEvidence()).isTrue();
        assertThat(result.invalidOutput()).isTrue();
    }

    @Test
    void all_citations_invalid_leaving_blank_answer_is_insufficient() {
        ValidatedKnowledgeAnswer result = validator.validate("[S2] [S5]", List.of(source(1)));

        assertThat(result.insufficientEvidence()).isTrue();
        assertThat(result.invalidCitationCount()).isEqualTo(2);
    }

    @Test
    void citations_keep_first_occurrence_order_without_duplicates() {
        List<KnowledgeSource> sources = List.of(source(2), source(1));
        ValidatedKnowledgeAnswer result = validator.validate("先 [S2] 后 [S1] 再 [S2]", sources);

        assertThat(result.citedSources()).extracting(KnowledgeSource::rank)
                .containsExactly(2, 1);
    }

    @Test
    void citation_rank_maps_to_the_correct_identity_for_cross_document_duplicate_hashes() {
        UUID chunkA = UUID.randomUUID();
        UUID documentA = UUID.randomUUID();
        UUID chunkB = UUID.randomUUID();
        UUID documentB = UUID.randomUUID();
        // 两条来源正文与 contentHash 相同（跨文档重复文本），但 documentId/chunkId 各自独立。
        KnowledgeSource first = new KnowledgeSource(chunkA, documentA, "原始件.pdf", "条款",
                "两件文档相同的条款", "same", 0.9, 1);
        KnowledgeSource second = new KnowledgeSource(chunkB, documentB, "复印件.pdf", "条款",
                "两件文档相同的条款", "same", 0.8, 2);

        ValidatedKnowledgeAnswer result =
                validator.validate("结论依据 [S2]，另见 [S1]。", List.of(first, second));

        assertThat(result.citedSources()).extracting(KnowledgeSource::rank).containsExactly(2, 1);
        // [S2] 必须落到第二条来源的身份上，不能与 [S1] 互换或合并成同一文档。
        assertThat(result.citedSources().getFirst().chunkId()).isEqualTo(chunkB);
        assertThat(result.citedSources().getFirst().documentId()).isEqualTo(documentB);
        assertThat(result.citedSources().get(1).chunkId()).isEqualTo(chunkA);
        assertThat(result.citedSources().get(1).documentId()).isEqualTo(documentA);
    }
}
