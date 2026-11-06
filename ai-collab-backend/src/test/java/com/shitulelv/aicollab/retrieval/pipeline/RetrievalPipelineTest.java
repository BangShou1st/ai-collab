package com.shitulelv.aicollab.retrieval.pipeline;

import com.shitulelv.aicollab.document.application.view.DocumentSearchHit;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RetrievalPipelineTest {

    @Test
    void testPipelineEndToEndExecution() {
        UUID projectId = UUID.randomUUID();
        UUID docId = UUID.randomUUID();
        UUID chunk1 = UUID.randomUUID();
        UUID chunk2 = UUID.randomUUID();
        UUID chunk3 = UUID.randomUUID();

        // Dense 检索返回 chunk1 与 chunk2
        Retriever denseRetriever = (proj, query, docIds, topK) -> List.of(
                new RetrievalCandidate(chunk1, docId, "arch.md", "混合架构", "第一句介绍向量检索。第二句介绍混合检索融合算法。", "h1",
                        Map.of("bodySnapshotId", "snap1", "charFrom", 0, "charThrough", 30),
                        1, 0.95, null, null, 0.0, null, null),
                new RetrievalCandidate(chunk2, docId, "arch.md", "数据库设计", "采用 PostgreSQL pgvector 存储高维向量。", "h2",
                        Map.of("bodySnapshotId", "snap1", "charFrom", 31, "charThrough", 60),
                        2, 0.85, null, null, 0.0, null, null)
        );

        // Lexical 检索返回 chunk1 与 chunk3
        Retriever lexicalRetriever = (proj, query, docIds, topK) -> List.of(
                new RetrievalCandidate(chunk1, docId, "arch.md", "混合架构", "第一句介绍向量检索。第二句介绍混合检索融合算法。", "h1",
                        Map.of("bodySnapshotId", "snap1", "charFrom", 0, "charThrough", 30),
                        null, null, 1, 0.90, 0.0, null, null),
                new RetrievalCandidate(chunk3, docId, "arch.md", "其他说明", "系统遵循不可变证据契约规范。", "h3",
                        Map.of("bodySnapshotId", "snap1", "charFrom", 61, "charThrough", 90),
                        null, null, 2, 0.75, 0.0, null, null)
        );

        String fullSection = "序章开启。第一句介绍向量检索。第二句介绍混合检索融合算法。尾声总结。";
        DocumentSectionReader sectionReader = (proj, doc, heading, snap) -> Optional.of(fullSection);

        SentenceWindowExpander swExpander = new SentenceWindowExpander(sectionReader);
        ParentChildExpander parentExpander = new ParentChildExpander();
        FusionStrategy fusion = new ReciprocalRankFusion();
        SemanticHeuristicReranker reranker = new SemanticHeuristicReranker();

        RetrievalPipeline pipeline = new RetrievalPipeline(
                denseRetriever,
                lexicalRetriever,
                fusion,
                swExpander,
                parentExpander,
                reranker
        );

        RetrievalPipeline.PipelineConfig config = new RetrievalPipeline.PipelineConfig(
                10, 10, 10, 2, true, 1, true
        );

        RetrievalResult result = pipeline.execute(projectId, "混合架构 向量检索", List.of(docId), config);

        assertThat(result).isNotNull();
        assertThat(result.candidates()).hasSize(2);

        // chunk1 在 dense 和 lexical 均为第一，且匹配 query，应排在首位
        RetrievalCandidate top = result.candidates().getFirst();
        assertThat(top.id()).isEqualTo(chunk1);
        assertThat(top.heading()).isEqualTo("混合架构");
        assertThat(top.expandedContent()).contains("序章开启。");
        assertThat(top.expandedContent()).contains("第一句介绍向量检索。");
        assertThat(top.expandedContent()).contains("第二句介绍混合检索融合算法。");
        assertThat(top.expandedContent()).contains("尾声总结。");

        // 验证 Trace 记录
        RetrievalTrace trace = result.trace();
        assertThat(trace).isNotNull();
        assertThat(trace.denseRecallCount()).isEqualTo(2);
        assertThat(trace.lexicalRecallCount()).isEqualTo(2);
        assertThat(trace.fusedCount()).isEqualTo(3);
        assertThat(trace.finalCount()).isEqualTo(2);
        assertThat(trace.rerankerDegraded()).isFalse();
        assertThat(trace.sentenceWindowExpanded()).isTrue();
        assertThat(trace.parentExpanded()).isTrue();
        assertThat(trace.rrfScores()).containsKey(chunk1);
        assertThat(trace.rrfScores()).containsKey(chunk2);
        assertThat(trace.rrfScores()).containsKey(chunk3);

        // 验证截断丢弃记录中包含未进入 top2 的 chunk
        assertThat(trace.dropReasons()).extracting(RetrievalTrace.DropRecord::reason)
                .contains("RERANK_TOP_N_TRUNCATED");

        // 验证转为 DocumentSearchHit
        List<DocumentSearchHit> hits = result.toDocumentSearchHits();
        assertThat(hits).hasSize(2);
        assertThat(hits.getFirst().id()).isEqualTo(chunk1);
        assertThat(hits.getFirst().similarity()).isGreaterThan(0.0);
    }

    @Test
    void testGracefulDegradationWhenRerankerFails() {
        UUID projectId = UUID.randomUUID();
        UUID docId = UUID.randomUUID();
        UUID chunk1 = UUID.randomUUID();

        Retriever denseRetriever = (proj, query, docIds, topK) -> List.of(
                new RetrievalCandidate(chunk1, docId, "d.md", "H", "C", "h",
                        Map.of(), 1, 0.9, null, null, 0.05, null, null)
        );

        Reranker failingReranker = new Reranker() {
            @Override
            public String name() {
                return "mock-failing";
            }

            @Override
            public List<RetrievalCandidate> rerank(String query, List<RetrievalCandidate> candidates, int topN) {
                throw new RuntimeException("Reranker model RPC timeout simulated");
            }
        };

        RetrievalPipeline pipeline = new RetrievalPipeline(
                denseRetriever,
                (proj, q, ids, k) -> List.of(),
                new ReciprocalRankFusion(),
                new SentenceWindowExpander(),
                new ParentChildExpander(),
                failingReranker
        );

        RetrievalResult result = pipeline.execute(projectId, "test query", List.of(docId));

        assertThat(result).isNotNull();
        assertThat(result.candidates()).hasSize(1);
        assertThat(result.candidates().getFirst().id()).isEqualTo(chunk1);

        // Trace 必须明确标记 rerankerDegraded = true
        assertThat(result.trace().rerankerDegraded()).isTrue();
        assertThat(result.trace().rerankerName()).isEqualTo("degraded");
    }

    @Test
    void testEmptyQueryReturnsEmptyResult() {
        RetrievalPipeline pipeline = new RetrievalPipeline(
                (p, q, ids, k) -> List.of(),
                (p, q, ids, k) -> List.of(),
                new ReciprocalRankFusion(),
                new SentenceWindowExpander(),
                new ParentChildExpander(),
                new PassThroughReranker()
        );

        RetrievalResult res = pipeline.execute(UUID.randomUUID(), "   ", List.of());
        assertThat(res.candidates()).isEmpty();
        assertThat(res.trace().finalCount()).isEqualTo(0);
        assertThat(res.trace().denseRecallCount()).isEqualTo(0);
    }
}
