package com.shitulelv.aicollab.retrieval.pipeline;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SentenceWindowExpanderTest {

    @Test
    void testSentenceBoundaryDetection() {
        String prose = "第一句结束。第二句带有叹号！第三句包含问号？第四句有分号；第五句是英文句子. "
                + "第六句包含数字 3.14 毫秒，不应误切。第七句他说：“引语内部有标点！”，引语应保持完整。"
                + "第八句带有括号（详见附录 A.1，包含 p99 说明）。最后是总结。";

        List<SentenceWindowExpander.SentenceSpan> sentences = SentenceWindowExpander.splitSentences(prose);

        assertThat(sentences).isNotEmpty();

        // 验证基本断句
        assertThat(sentences.get(0).text()).isEqualTo("第一句结束。");
        assertThat(sentences.get(1).text()).isEqualTo("第二句带有叹号！");
        assertThat(sentences.get(2).text()).isEqualTo("第三句包含问号？");
        assertThat(sentences.get(3).text()).isEqualTo("第四句有分号；");

        // 验证数字 3.14 不被切碎
        SentenceWindowExpander.SentenceSpan numSentence = sentences.stream()
                .filter(s -> s.text().contains("3.14"))
                .findFirst()
                .orElseThrow();
        assertThat(numSentence.text()).contains("3.14 毫秒");

        // 验证引语完整保护
        SentenceWindowExpander.SentenceSpan quoteSentence = sentences.stream()
                .filter(s -> s.text().contains("引语内部有标点"))
                .findFirst()
                .orElseThrow();
        assertThat(quoteSentence.text()).contains("“引语内部有标点！”");

        // 验证括号完整保护
        SentenceWindowExpander.SentenceSpan bracketSentence = sentences.stream()
                .filter(s -> s.text().contains("详见附录"))
                .findFirst()
                .orElseThrow();
        assertThat(bracketSentence.text()).contains("（详见附录 A.1，包含 p99 说明）");
    }

    @Test
    void testWindowExpansionWithReader() {
        UUID docId = UUID.randomUUID();
        String fullSection = "第一句介绍背景。第二句描述痛点。第三句提出解决方案。第四句展示实验数据。第五句给出未来展望。";

        DocumentSectionReader reader = (proj, doc, heading, snap) -> Optional.of(fullSection);
        SentenceWindowExpander expander = new SentenceWindowExpander(reader);

        // 命中 "第三句提出解决方案。"
        RetrievalCandidate candidate = new RetrievalCandidate(
                UUID.randomUUID(), docId, "doc.md", "方案介绍", "第三句提出解决方案。", "hash3",
                Map.of("bodySnapshotId", "snap1", "charFrom", 20, "charThrough", 31),
                1, 0.9, null, null, 0.05, null, null
        );

        // 默认窗口大小为 1，展开前后各 1 句 -> 包含第二句、第三句、第四句
        List<RetrievalCandidate> expanded = expander.expand(List.of(candidate), 1);

        assertThat(expanded).hasSize(1);
        RetrievalCandidate res = expanded.getFirst();

        // 契约校验：原始 content 与坐标不受损坏
        assertThat(res.content()).isEqualTo("第三句提出解决方案。");
        assertThat(res.metadata().get("charFrom")).isEqualTo(20);
        assertThat(res.metadata().get("charThrough")).isEqualTo(31);

        // 扩展内容恢复前后句
        String expandedText = res.expandedContent();
        assertThat(expandedText).contains("第二句描述痛点。");
        assertThat(expandedText).contains("第三句提出解决方案。");
        assertThat(expandedText).contains("第四句展示实验数据。");
        assertThat(expandedText).doesNotContain("第一句介绍背景。");
        assertThat(expandedText).doesNotContain("第五句给出未来展望。");

        assertThat(res.metadata()).containsEntry("windowSentenceCount", 3);
    }

    @Test
    void testTableAndCodeBlockSkipped() {
        UUID docId = UUID.randomUUID();
        SentenceWindowExpander expander = new SentenceWindowExpander();

        // Markdown 表格
        String tableContent = """
                | 列1 | 列2 |
                |---|---|
                | 数据1 | 数据2 |
                """;
        RetrievalCandidate tableCandidate = new RetrievalCandidate(
                UUID.randomUUID(), docId, "t.md", "数据表", tableContent, "tableHash",
                Map.of("charFrom", 0, "charThrough", 50),
                1, 0.9, null, null, 0.05, null, null
        );

        // 代码块
        String codeContent = """
                ```java
                public void hello() {
                    System.out.println("world");
                }
                ```
                """;
        RetrievalCandidate codeCandidate = new RetrievalCandidate(
                UUID.randomUUID(), docId, "c.md", "代码", codeContent, "codeHash",
                Map.of("charFrom", 60, "charThrough", 120),
                2, 0.8, null, null, 0.04, null, null
        );

        List<RetrievalCandidate> res = expander.expand(List.of(tableCandidate, codeCandidate), 1);

        assertThat(res).hasSize(2);
        for (RetrievalCandidate cand : res) {
            assertThat(cand.metadata()).containsEntry("sentenceWindowSkipped", "TABLE_OR_CODE_BLOCK");
            assertThat(cand.expandedContent()).isNull();
            assertThat(cand.effectiveContent()).isEqualTo(cand.content());
        }
    }

    @Test
    void testOverlapMergingAndDeduplication() {
        UUID docId = UUID.randomUUID();
        UUID chunk1 = UUID.randomUUID();
        UUID chunk2 = UUID.randomUUID();

        String section = "第零句起步。第一句介绍。第二句深入。第三句实验。第四句总结。";
        DocumentSectionReader reader = (proj, doc, heading, snap) -> Optional.of(section);
        SentenceWindowExpander expander = new SentenceWindowExpander(reader);

        // chunk1 命中第一句（展开前后 1 句：第零句、第一句、第二句）
        RetrievalCandidate c1 = new RetrievalCandidate(
                chunk1, docId, "doc.md", "第一节", "第一句介绍。", "h1",
                Map.of("bodySnapshotId", "snap1", "charFrom", 6, "charThrough", 12),
                1, 0.95, null, null, 0.030, 0.85, "test"
        );

        // chunk2 命中第二句（展开前后 1 句：第一句、第二句、第三句）
        RetrievalCandidate c2 = new RetrievalCandidate(
                chunk2, docId, "doc.md", "第一节", "第二句深入。", "h2",
                Map.of("bodySnapshotId", "snap1", "charFrom", 13, "charThrough", 19),
                2, 0.85, null, null, 0.020, 0.75, "test"
        );

        // 两者窗口在 第一句 和 第二句 发生重叠！
        List<RetrievalCandidate> expanded = expander.expand(List.of(c1, c2), 1);

        // 窗口重叠时自动合并去重，只保留 1 个合并后的候选！
        assertThat(expanded).hasSize(1);
        RetrievalCandidate merged = expanded.getFirst();

        // 选取得分更高的 c1 作为代表 chunk
        assertThat(merged.id()).isEqualTo(chunk1);
        assertThat(merged.metadata()).containsEntry("sentenceWindowMerged", true);
        @SuppressWarnings("unchecked")
        List<UUID> mergedChunkIds = (List<UUID>) merged.metadata().get("mergedChunkIds");
        assertThat(mergedChunkIds).containsExactlyInAnyOrder(chunk1, chunk2);

        // 合并区间应覆盖第零句到第三句
        String exp = merged.expandedContent();
        assertThat(exp).contains("第零句起步。");
        assertThat(exp).contains("第一句介绍。");
        assertThat(exp).contains("第二句深入。");
        assertThat(exp).contains("第三句实验。");

        // 契约验证：c1 的原始引用坐标完全保留，没有被破坏
        assertThat(merged.content()).isEqualTo("第一句介绍。");
        assertThat(merged.metadata().get("charFrom")).isEqualTo(6);
        assertThat(merged.metadata().get("charThrough")).isEqualTo(12);
    }

    @Test
    void testNoCrossBoundaryMerge() {
        // 验证严禁跨文档、跨章节、跨版本合并
        UUID doc1 = UUID.randomUUID();
        UUID doc2 = UUID.randomUUID();

        String section = "句A。句B。句C。";
        DocumentSectionReader reader = (proj, doc, heading, snap) -> Optional.of(section);
        SentenceWindowExpander expander = new SentenceWindowExpander(reader);

        // c1: doc1, snap1, 章节一
        RetrievalCandidate c1 = new RetrievalCandidate(
                UUID.randomUUID(), doc1, "d1.md", "章节一", "句A。", "h1",
                Map.of("bodySnapshotId", "snap1"), 1, 0.9, null, null, 0.03, null, null
        );

        // c2: doc2 (跨文档), snap1, 章节一
        RetrievalCandidate c2 = new RetrievalCandidate(
                UUID.randomUUID(), doc2, "d2.md", "章节一", "句B。", "h2",
                Map.of("bodySnapshotId", "snap1"), 2, 0.8, null, null, 0.02, null, null
        );

        // c3: doc1, snap1, 章节二 (跨章节)
        RetrievalCandidate c3 = new RetrievalCandidate(
                UUID.randomUUID(), doc1, "d1.md", "章节二", "句B。", "h3",
                Map.of("bodySnapshotId", "snap1"), 3, 0.7, null, null, 0.01, null, null
        );

        // c4: doc1, snap2 (跨版本), 章节一
        RetrievalCandidate c4 = new RetrievalCandidate(
                UUID.randomUUID(), doc1, "d1.md", "章节一", "句B。", "h4",
                Map.of("bodySnapshotId", "snap2"), 4, 0.6, null, null, 0.009, null, null
        );

        List<RetrievalCandidate> results = expander.expand(List.of(c1, c2, c3, c4), 1);

        // 绝不跨边界合并，4 个候选必须全部独立保留
        assertThat(results).hasSize(4);
        for (RetrievalCandidate cand : results) {
            assertThat(cand.metadata().get("sentenceWindowMerged")).isEqualTo(false);
        }
    }
}
