package com.shitulelv.aicollab.knowledge.domain.service;

import com.shitulelv.aicollab.document.application.view.DocumentSearchHit;
import com.shitulelv.aicollab.knowledge.domain.model.KnowledgeContext;
import com.shitulelv.aicollab.knowledge.domain.model.KnowledgeSource;
import com.shitulelv.aicollab.knowledge.domain.model.ValidatedKnowledgeAnswer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P4-RAG-QA-SOURCE-IDENTITY-01 确定性验收测试套件：
 * 验证普通知识库问答跨文档来源身份保留，以及同文档去重、名额竞争、引用身份和边界条件。
 */
class CrossDocumentSourceIdentityFixTest {

    private final KnowledgeContextBuilder builder = new KnowledgeContextBuilder();
    private final KnowledgeCitationValidator validator = new KnowledgeCitationValidator();

    private static DocumentSearchHit hit(
            UUID chunkId, UUID documentId, String filename, String heading,
            String content, String contentHash, double similarity) {
        return new DocumentSearchHit(
                chunkId, documentId, filename, heading, content, contentHash, similarity,
                Map.of("pageNumber", 1));
    }

    @Nested
    @DisplayName("A. Q09 原始反例复现与修复验证")
    class ScenarioA_Q09OriginalCounterexample {

        @Test
        @DisplayName("合同模板乙与合同模板甲相同条款应分别保留独立来源身份，不被全局哈希去重")
        void bothContractsShouldBeRetainedAsDistinctSources() {
            UUID docA = UUID.randomUUID();
            UUID docB = UUID.randomUUID();
            UUID chunkA = UUID.randomUUID();
            UUID chunkB = UUID.randomUUID();
            String sharedClause = "重大变更须经项目负责人书面批准后方可实施。";
            String sharedHash = "42b0da85989916ceaa21bc55990bda21a4ba8fe1dcfe6807d27768f763f8b775";

            // pgvector 实际检索结果：Rank1 合同乙，Rank2 合同甲
            List<DocumentSearchHit> candidates = List.of(
                    hit(chunkB, docB, "合同模板乙.md", "重大变更审批", sharedClause, sharedHash, 0.6751),
                    hit(chunkA, docA, "合同模板甲.md", "重大变更审批", sharedClause, sharedHash, 0.6751),
                    hit(UUID.randomUUID(), UUID.randomUUID(), "需求变更单.md", "早期版本说明",
                            "早期版本 v1.9 约定...", "hash-3", 0.5226)
            );

            KnowledgeContext context = builder.build(candidates);

            // 修复要求：两份合同来源身份均保留
            assertThat(context.sources()).hasSize(3);
            assertThat(context.sources().get(0).documentId()).isEqualTo(docB);
            assertThat(context.sources().get(0).chunkId()).isEqualTo(chunkB);
            assertThat(context.sources().get(0).originalFilename()).isEqualTo("合同模板乙.md");
            assertThat(context.sources().get(0).rank()).isEqualTo(1);

            assertThat(context.sources().get(1).documentId()).isEqualTo(docA);
            assertThat(context.sources().get(1).chunkId()).isEqualTo(chunkA);
            assertThat(context.sources().get(1).originalFilename()).isEqualTo("合同模板甲.md");
            assertThat(context.sources().get(1).rank()).isEqualTo(2);

            assertThat(context.promptSources()).contains("[S1]\n文件：合同模板乙.md");
            assertThat(context.promptSources()).contains("[S2]\n文件：合同模板甲.md");
        }
    }

    @Nested
    @DisplayName("B. 同文档正文重复折叠")
    class ScenarioB_IntraDocumentDuplicate {

        @Test
        @DisplayName("同一文档内两个相同正文分块应去重，第二个分块被归为 INTRA_DOCUMENT_DUPLICATE")
        void intraDocumentDuplicatesAreCollapsed() {
            UUID doc = UUID.randomUUID();
            UUID chunk1 = UUID.randomUUID();
            UUID chunk2 = UUID.randomUUID();
            UUID chunk3 = UUID.randomUUID();
            String content = "同一文档内完全相同的重复条款内容";
            String hash = "intra-hash";

            List<DocumentSearchHit> candidates = List.of(
                    hit(chunk1, doc, "手册.md", "章节1", content, hash, 0.90),
                    hit(chunk2, doc, "手册.md", "章节2", content, hash, 0.85),
                    hit(chunk3, doc, "手册.md", "章节3", "不同内容", "other-hash", 0.80)
            );

            KnowledgeContextBuilder.BuildResult result = builder.buildWithDiagnostics(candidates, 5);

            assertThat(result.context().sources()).hasSize(2);
            assertThat(result.context().sources().get(0).chunkId()).isEqualTo(chunk1);
            assertThat(result.context().sources().get(1).chunkId()).isEqualTo(chunk3);

            assertThat(result.dropReasons()).containsEntry(chunk2, ContextDropReason.INTRA_DOCUMENT_DUPLICATE);
        }
    }

    @Nested
    @DisplayName("C. 来源名额紧张与资源竞争")
    class ScenarioC_ResourceCompetition {

        @Test
        @DisplayName("5个名额满额时，高相似度来源按顺序入选，第6条因 SOURCE_CAP 被正常剔除")
        void slotCompetitionRespectsOrderingAndCap() {
            UUID docA = UUID.randomUUID();
            UUID docB = UUID.randomUUID();
            String sharedClause = "两份合同相同的重大变更条款";
            String sharedHash = "contract-shared-hash";

            UUID chunkB = UUID.randomUUID();
            UUID chunkA = UUID.randomUUID();
            UUID chunk3 = UUID.randomUUID();
            UUID chunk4 = UUID.randomUUID();
            UUID chunk5 = UUID.randomUUID();
            UUID chunk6 = UUID.randomUUID();

            List<DocumentSearchHit> candidates = List.of(
                    hit(chunkB, docB, "合同乙.md", "条款", sharedClause, sharedHash, 0.95),
                    hit(chunkA, docA, "合同甲.md", "条款", sharedClause, sharedHash, 0.90),
                    hit(chunk3, UUID.randomUUID(), "需求单.md", "需求3", "内容3", "h3", 0.85),
                    hit(chunk4, UUID.randomUUID(), "需求单.md", "需求4", "内容4", "h4", 0.80),
                    hit(chunk5, UUID.randomUUID(), "预算表.md", "预算5", "内容5", "h5", 0.75),
                    hit(chunk6, UUID.randomUUID(), "附录.md", "附录6", "内容6", "h6", 0.70)
            );

            KnowledgeContextBuilder.BuildResult result = builder.buildWithDiagnostics(candidates, 5);

            // 前 5 条按顺序保留（包含合同乙与合同甲），第 6 条被 SOURCE_CAP 丢弃
            assertThat(result.context().sources()).hasSize(5);
            assertThat(result.context().sources()).extracting(KnowledgeSource::chunkId)
                    .containsExactly(chunkB, chunkA, chunk3, chunk4, chunk5);
            assertThat(result.dropReasons()).containsEntry(chunk6, ContextDropReason.SOURCE_CAP);
        }
    }

    @Nested
    @DisplayName("D. 来源身份与引用校验")
    class ScenarioD_CitationIdentityAndValidation {

        @Test
        @DisplayName("双合同同时存在时，模型引用 [S1] 和 [S2] 能准确绑定到对应文档与分块")
        void citationsAccuratelyMapToIndependentDocuments() {
            UUID docA = UUID.randomUUID();
            UUID docB = UUID.randomUUID();
            UUID chunkA = UUID.randomUUID();
            UUID chunkB = UUID.randomUUID();
            String sharedClause = "重大变更须经项目负责人书面批准后方可实施。";
            String sharedHash = "shared-hash";

            List<DocumentSearchHit> candidates = List.of(
                    hit(chunkB, docB, "合同模板乙.md", "重大变更审批", sharedClause, sharedHash, 0.6751),
                    hit(chunkA, docA, "合同模板甲.md", "重大变更审批", sharedClause, sharedHash, 0.6751)
            );

            KnowledgeContext context = builder.build(candidates);

            String modelAnswer = "合同模板乙要求经项目负责人书面批准 [S1]，合同模板甲同样要求经项目负责人书面批准 [S2]，两者要求一致。";
            ValidatedKnowledgeAnswer validated = validator.validate(modelAnswer, context.sources());

            assertThat(validated.insufficientEvidence()).isFalse();
            assertThat(validated.invalidCitationCount()).isEqualTo(0);
            assertThat(validated.citedSources()).hasSize(2);

            // [S1] 映射到合同模板乙
            KnowledgeSource citation1 = validated.citedSources().get(0);
            assertThat(citation1.chunkId()).isEqualTo(chunkB);
            assertThat(citation1.documentId()).isEqualTo(docB);
            assertThat(citation1.originalFilename()).isEqualTo("合同模板乙.md");
            assertThat(citation1.rank()).isEqualTo(1);

            // [S2] 映射到合同模板甲
            KnowledgeSource citation2 = validated.citedSources().get(1);
            assertThat(citation2.chunkId()).isEqualTo(chunkA);
            assertThat(citation2.documentId()).isEqualTo(docA);
            assertThat(citation2.originalFilename()).isEqualTo("合同模板甲.md");
            assertThat(citation2.rank()).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("E. 项目隔离与文档范围验证")
    class ScenarioE_ScopeAndIsolation {

        @Test
        @DisplayName("范围限定单文档时，只有该文档的分块进入上下文，不混淆外部文档")
        void scopedDocumentCandidatesRemainStrictlyIsolated() {
            UUID docA = UUID.randomUUID();
            UUID chunkA = UUID.randomUUID();

            List<DocumentSearchHit> candidates = List.of(
                    hit(chunkA, docA, "合同模板甲.md", "审批", "正文条款", "hash-a", 0.88)
            );

            KnowledgeContext context = builder.build(candidates);

            assertThat(context.sources()).hasSize(1);
            assertThat(context.sources().getFirst().documentId()).isEqualTo(docA);
            assertThat(context.sources().getFirst().originalFilename()).isEqualTo("合同模板甲.md");
        }
    }

    @Nested
    @DisplayName("F. 稳定排序与边界条件")
    class ScenarioF_StableOrderingAndEdgeCases {

        @Test
        @DisplayName("重复传入相同 chunkId 时应作为重复分块丢弃，不能重复占位")
        void duplicateChunkIdIsDeduplicated() {
            UUID doc = UUID.randomUUID();
            UUID chunk = UUID.randomUUID();

            List<DocumentSearchHit> candidates = List.of(
                    hit(chunk, doc, "文档.md", "章节", "有效内容", "hash-1", 0.90),
                    hit(chunk, doc, "文档.md", "章节", "有效内容", "hash-1", 0.90)
            );

            KnowledgeContextBuilder.BuildResult result = builder.buildWithDiagnostics(candidates, 5);

            assertThat(result.context().sources()).hasSize(1);
            assertThat(result.dropReasons()).containsEntry(chunk, ContextDropReason.INTRA_DOCUMENT_DUPLICATE);
        }

        @Test
        @DisplayName("低于相似度阈值或空白文本应被丢弃，长正文不能突破 8000 字符预算")
        void thresholdAndBudgetGuardsAreEnforced() {
            UUID chunkLow = UUID.randomUUID();
            UUID chunkBlank = UUID.randomUUID();
            UUID chunkHuge = UUID.randomUUID();
            UUID chunkValid = UUID.randomUUID();

            List<DocumentSearchHit> candidates = List.of(
                    hit(chunkLow, UUID.randomUUID(), "低.md", "标", "低分内容", "h-low", 0.10),
                    hit(chunkBlank, UUID.randomUUID(), "空.md", "标", "   ", "h-blank", 0.90),
                    hit(chunkHuge, UUID.randomUUID(), "巨.md", "标", "超长文本😀".repeat(2000), "h-huge", 0.85),
                    hit(chunkValid, UUID.randomUUID(), "正.md", "标", "正常有效证据", "h-valid", 0.80)
            );

            KnowledgeContextBuilder.BuildResult result = builder.buildWithDiagnostics(candidates, 5);

            assertThat(result.dropReasons()).containsEntry(chunkLow, ContextDropReason.BELOW_SIMILARITY_THRESHOLD);
            assertThat(result.dropReasons()).containsEntry(chunkBlank, ContextDropReason.EMPTY_BODY);
            // 巨型文本虽然被容纳，但会被截断，不能超过 8000 code points
            assertThat(result.context().promptSources().codePointCount(0, result.context().promptSources().length()))
                    .isLessThanOrEqualTo(8000);
        }
    }
}
