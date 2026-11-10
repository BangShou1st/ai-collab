package com.shitulelv.aicollab.retrieval.pipeline;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RerankerTest {

    @Test
    void testEvidenceImmutabilityContract() {
        UUID chunkId = UUID.randomUUID();
        UUID docId = UUID.randomUUID();
        String originalText = "原始文档段落内容，不可篡改。";
        String originalHash = "sha256_unmodifiable_hash";
        Map<String, Object> metadata = Map.of("charFrom", 100, "charThrough", 200, "pageNumber", 5);

        RetrievalCandidate original = new RetrievalCandidate(
                chunkId,
                docId,
                "project-spec.pdf",
                "系统架构",
                originalText,
                originalHash,
                metadata,
                1,
                0.92,
                2,
                0.88,
                0.032,
                null,
                null
        );

        SemanticHeuristicReranker heuristicReranker = new SemanticHeuristicReranker();
        PassThroughReranker passThroughReranker = new PassThroughReranker();

        List<RetrievalCandidate> heuristicResults = heuristicReranker.rerank("系统架构 核心设计", List.of(original), 5);
        List<RetrievalCandidate> passThroughResults = passThroughReranker.rerank("系统架构 核心设计", List.of(original), 5);

        for (List<RetrievalCandidate> results : List.of(heuristicResults, passThroughResults)) {
            assertThat(results).hasSize(1);
            RetrievalCandidate cand = results.getFirst();

            // 契约断言：严禁修改任何原始身份标识与文本内容
            assertThat(cand.id()).isEqualTo(chunkId);
            assertThat(cand.documentId()).isEqualTo(docId);
            assertThat(cand.originalFilename()).isEqualTo("project-spec.pdf");
            assertThat(cand.heading()).isEqualTo("系统架构");
            assertThat(cand.content()).isEqualTo(originalText);
            assertThat(cand.contentHash()).isEqualTo(originalHash);
            assertThat(cand.metadata().get("charFrom")).isEqualTo(100);
            assertThat(cand.metadata().get("charThrough")).isEqualTo(200);
            assertThat(cand.metadata().get("pageNumber")).isEqualTo(5);

            // 原始多路召回记录必须完整保留
            assertThat(cand.denseRank()).isEqualTo(1);
            assertThat(cand.denseSimilarity()).isEqualTo(0.92);
            assertThat(cand.lexicalRank()).isEqualTo(2);
            assertThat(cand.lexicalScore()).isEqualTo(0.88);
            assertThat(cand.rrfScore()).isEqualTo(0.032);
        }
    }

    @Test
    void testSemanticHeuristicRanking() {
        UUID docId = UUID.randomUUID();

        // 候选 A: 标题精确匹配 "系统架构设计"
        RetrievalCandidate candA = new RetrievalCandidate(
                UUID.randomUUID(), docId, "a.md", "系统架构设计", "本文档主要阐述整体模块布局。", "hashA",
                Map.of(), 3, 0.70, 3, 0.70, 0.015, null, null
        );

        // 候选 B: 正文包含高密度查询词 "混合检索" 与 "pgvector"
        RetrievalCandidate candB = new RetrievalCandidate(
                UUID.randomUUID(), docId, "b.md", "模块说明", "本模块采用混合检索技术，结合了 pgvector 向量检索与全文检索。", "hashB",
                Map.of(), 2, 0.80, 2, 0.80, 0.020, null, null
        );

        // 候选 C: 完全无关内容
        RetrievalCandidate candC = new RetrievalCandidate(
                UUID.randomUUID(), docId, "c.md", "会议纪要", "今天下午两点在三楼会议室召开例会，讨论考勤事宜。", "hashC",
                Map.of(), 1, 0.85, 1, 0.85, 0.032, null, null
        );

        SemanticHeuristicReranker reranker = new SemanticHeuristicReranker();

        // 查询包含 "架构" 和 "混合检索"
        List<RetrievalCandidate> reranked = reranker.rerank("系统架构 混合检索", List.of(candA, candB, candC), 3);

        assertThat(reranked).hasSize(3);

        // candA (标题完全覆盖架构) 与 candB (内容覆盖混合检索) 应排在无关的 candC 前面
        assertThat(reranked.get(0).id()).isIn(candA.id(), candB.id());
        assertThat(reranked.get(1).id()).isIn(candA.id(), candB.id());
        assertThat(reranked.get(2).id()).isEqualTo(candC.id());

        // 检查 rerankScore 是否已正确赋值
        assertThat(reranked.get(0).rerankScore()).isGreaterThan(reranked.get(2).rerankScore());
        assertThat(reranked.get(0).rerankerName()).isEqualTo(SemanticHeuristicReranker.NAME);
    }

    @Test
    void testSafeDegradationOnEmptyOrException() {
        UUID docId = UUID.randomUUID();
        RetrievalCandidate cand = new RetrievalCandidate(
                UUID.randomUUID(), docId, "test.md", "标题", "正文", "hash",
                Map.of(), 1, 0.9, 1, 0.9, 0.03, null, null
        );

        SemanticHeuristicReranker reranker = new SemanticHeuristicReranker();

        // 传入空白查询或空候选，平滑降级
        List<RetrievalCandidate> res1 = reranker.rerank("", List.of(cand), 5);
        assertThat(res1).hasSize(1);
        assertThat(res1.getFirst().metadata()).containsEntry("rerankerDegraded", true);
        assertThat(res1.getFirst().rerankerName()).isEqualTo("degraded");
    }

    @Test
    void testPassThroughRerankerKeepsOrderAndSlices() {
        UUID docId = UUID.randomUUID();
        RetrievalCandidate c1 = new RetrievalCandidate(
                UUID.randomUUID(), docId, "1.md", "H1", "C1", "h1",
                Map.of(), 1, 0.9, null, null, 0.05, null, null
        );
        RetrievalCandidate c2 = new RetrievalCandidate(
                UUID.randomUUID(), docId, "2.md", "H2", "C2", "h2",
                Map.of(), 2, 0.8, null, null, 0.03, null, null
        );

        PassThroughReranker reranker = new PassThroughReranker();
        List<RetrievalCandidate> res = reranker.rerank("query", List.of(c1, c2), 1);

        assertThat(res).hasSize(1);
        assertThat(res.getFirst().id()).isEqualTo(c1.id());
        assertThat(res.getFirst().rerankerName()).isEqualTo(PassThroughReranker.NAME);
    }
}
