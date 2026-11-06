package com.shitulelv.aicollab.retrieval.pipeline;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReciprocalRankFusionTest {

    private final ReciprocalRankFusion fusion = new ReciprocalRankFusion();

    @Test
    void testRrfFormulaCalculation() {
        UUID docId = UUID.randomUUID();
        UUID chunk1 = UUID.randomUUID();
        UUID chunk2 = UUID.randomUUID();
        UUID chunk3 = UUID.randomUUID();

        // Dense 召回 chunk1 (rank 1), chunk2 (rank 2)
        RetrievalCandidate d1 = new RetrievalCandidate(
                chunk1, docId, "doc1.txt", "H1", "内容1", "hash1",
                Map.of(), 1, 0.95, null, null, 0.0, null, null
        );
        RetrievalCandidate d2 = new RetrievalCandidate(
                chunk2, docId, "doc1.txt", "H1", "内容2", "hash2",
                Map.of(), 2, 0.85, null, null, 0.0, null, null
        );

        // Lexical 召回 chunk1 (rank 2), chunk3 (rank 1)
        RetrievalCandidate l1 = new RetrievalCandidate(
                chunk3, docId, "doc1.txt", "H1", "内容3", "hash3",
                Map.of(), null, null, 1, 0.90, 0.0, null, null
        );
        RetrievalCandidate l2 = new RetrievalCandidate(
                chunk1, docId, "doc1.txt", "H1", "内容1", "hash1",
                Map.of(), null, null, 2, 0.80, 0.0, null, null
        );

        List<RetrievalCandidate> fused = fusion.fuse(List.of(d1, d2), List.of(l1, l2), 10);

        assertThat(fused).hasSize(3);

        // chunk1 出现在 dense rank 1 和 lexical rank 2
        // RRF = 1.0 / (60 + 1) + 1.0 / (60 + 2) = 1/61 + 1/62 ≈ 0.0163934 + 0.0161290 = 0.032522
        RetrievalCandidate res1 = fused.stream().filter(c -> c.id().equals(chunk1)).findFirst().orElseThrow();
        double expectedChunk1 = (1.0 / 61.0) + (1.0 / 62.0);
        assertThat(res1.rrfScore()).isCloseTo(expectedChunk1, org.assertj.core.data.Offset.offset(1e-6));
        assertThat(res1.denseRank()).isEqualTo(1);
        assertThat(res1.denseSimilarity()).isEqualTo(0.95);
        assertThat(res1.lexicalRank()).isEqualTo(2);
        assertThat(res1.lexicalScore()).isEqualTo(0.80);

        // chunk3 仅出现在 lexical rank 1: 1.0 / 61.0 ≈ 0.0163934
        RetrievalCandidate res3 = fused.stream().filter(c -> c.id().equals(chunk3)).findFirst().orElseThrow();
        double expectedChunk3 = 1.0 / 61.0;
        assertThat(res3.rrfScore()).isCloseTo(expectedChunk3, org.assertj.core.data.Offset.offset(1e-6));
        assertThat(res3.denseRank()).isNull();
        assertThat(res3.lexicalRank()).isEqualTo(1);

        // chunk2 仅出现在 dense rank 2: 1.0 / 62.0 ≈ 0.0161290
        RetrievalCandidate res2 = fused.stream().filter(c -> c.id().equals(chunk2)).findFirst().orElseThrow();
        double expectedChunk2 = 1.0 / 62.0;
        assertThat(res2.rrfScore()).isCloseTo(expectedChunk2, org.assertj.core.data.Offset.offset(1e-6));
        assertThat(res2.denseRank()).isEqualTo(2);
        assertThat(res2.lexicalRank()).isNull();

        // 排序：chunk1 (0.0325) > chunk3 (0.01639) > chunk2 (0.01612)
        assertThat(fused.get(0).id()).isEqualTo(chunk1);
        assertThat(fused.get(1).id()).isEqualTo(chunk3);
        assertThat(fused.get(2).id()).isEqualTo(chunk2);
    }

    @Test
    void testDeduplicationByChunkIdNotContentHash() {
        // 关键契约测试：来自不同文档的相同文本具有相同 contentHash，但严禁被合并！
        UUID docA = UUID.randomUUID();
        UUID docB = UUID.randomUUID();
        UUID chunkA = UUID.randomUUID();
        UUID chunkB = UUID.randomUUID();

        String identicalContent = "系统采用多路混合检索架构。";
        String identicalHash = "hash_identical_999";

        RetrievalCandidate c1 = new RetrievalCandidate(
                chunkA, docA, "docA.txt", "节1", identicalContent, identicalHash,
                Map.of("doc", "A"), 1, 0.90, null, null, 0.0, null, null
        );

        RetrievalCandidate c2 = new RetrievalCandidate(
                chunkB, docB, "docB.txt", "节2", identicalContent, identicalHash,
                Map.of("doc", "B"), null, null, 1, 0.85, 0.0, null, null
        );

        List<RetrievalCandidate> fused = fusion.fuse(List.of(c1), List.of(c2), 10);

        // 严禁按文本哈希去重合并！必须保留两个来源不同的 candidate
        assertThat(fused).hasSize(2);
        assertThat(fused).extracting(RetrievalCandidate::id).containsExactlyInAnyOrder(chunkA, chunkB);
        assertThat(fused).extracting(RetrievalCandidate::documentId).containsExactlyInAnyOrder(docA, docB);

        RetrievalCandidate outA = fused.stream().filter(c -> c.id().equals(chunkA)).findFirst().orElseThrow();
        assertThat(outA.documentId()).isEqualTo(docA);
        assertThat(outA.originalFilename()).isEqualTo("docA.txt");
        assertThat(outA.metadata()).containsEntry("doc", "A");

        RetrievalCandidate outB = fused.stream().filter(c -> c.id().equals(chunkB)).findFirst().orElseThrow();
        assertThat(outB.documentId()).isEqualTo(docB);
        assertThat(outB.originalFilename()).isEqualTo("docB.txt");
        assertThat(outB.metadata()).containsEntry("doc", "B");
    }

    @Test
    void testStableSortingAndTieBreaker() {
        // 历史缺陷固化说明：旧比较器在 rrfScore 平分时按 max(denseSimilarity, lexicalScore) 降序
        // 决定次序，把余弦相似度（0.90）与 Lexical 匹配强度（0.95）两套不可比分数放在同一标尺上
        // ——与 KnowledgeContextBuilder 阈值混淆同源的跨分数类型语义错误，已按类型化评分契约移除。
        UUID docId = UUID.randomUUID();
        UUID chunk1 = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID chunk2 = UUID.fromString("00000000-0000-0000-0000-000000000002");

        // chunk1 在 dense rank 1，denseSimilarity = 0.90
        RetrievalCandidate d1 = new RetrievalCandidate(
                chunk1, docId, "doc1.txt", "H", "C1", "h1",
                Map.of(), 1, 0.90, null, null, 0.0, null, null
        );
        // chunk2 在 lexical rank 1，lexicalScore = 0.95
        RetrievalCandidate l2 = new RetrievalCandidate(
                chunk2, docId, "doc1.txt", "H", "C2", "h2",
                Map.of(), null, null, 1, 0.95, 0.0, null, null
        );

        // RRF 得分均为 1/(60+1)
        List<RetrievalCandidate> fused = fusion.fuse(List.of(d1), List.of(l2), 10);

        assertThat(fused).hasSize(2);
        // rrfScore 相同、单路召回路数相同、最优排名相同（均为 rank 1）：
        // tie-break 只用排名空间证据，最终按 chunkId 升序稳定收敛；
        // 严禁回退到比较 denseSimilarity 与 lexicalScore 的分数值。
        assertThat(fused.get(0).id()).isEqualTo(chunk1);
        assertThat(fused.get(1).id()).isEqualTo(chunk2);
    }

    @Test
    void testTopKTruncation() {
        UUID docId = UUID.randomUUID();
        List<RetrievalCandidate> denseList = new java.util.ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            denseList.add(new RetrievalCandidate(
                    UUID.randomUUID(), docId, "f.txt", "H", "C" + i, "hash" + i,
                    Map.of(), i, 1.0 / i, null, null, 0.0, null, null
            ));
        }

        List<RetrievalCandidate> fused = fusion.fuse(denseList, List.of(), 3);
        assertThat(fused).hasSize(3);
    }

    @Test
    void testInvalidKParameterThrows() {
        assertThatThrownBy(() -> new ReciprocalRankFusion(0, 1.0, 1.0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
