package com.shitulelv.aicollab.retrieval.pipeline;

import com.shitulelv.aicollab.document.application.view.DocumentSearchHit;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ParentChildExpanderTest {

    @Test
    void testExpandViaParentSnippet() {
        UUID docId = UUID.randomUUID();
        String parentSnippet = "这是所属章节的概览内容，包含核心架构与组件划分定义。";

        RetrievalCandidate child = new RetrievalCandidate(
                UUID.randomUUID(), docId, "arch.md", "详细组件实现", "子分块具体实现代码细节。", "childHash",
                Map.of(
                        "chunkType", "CHILD",
                        "parentHeading", "系统架构总览",
                        "parentSnippet", parentSnippet,
                        "bodySnapshotId", "snap1"
                ),
                1, 0.9, null, null, 0.05, null, null
        );

        ParentChildExpander expander = new ParentChildExpander();
        List<RetrievalCandidate> res = expander.expand(UUID.randomUUID(), List.of(child));

        assertThat(res).hasSize(1);
        RetrievalCandidate candidate = res.getFirst();

        assertThat(candidate.parentContext()).isEqualTo(parentSnippet);
        assertThat(candidate.metadata().get("parentHeading")).isEqualTo("系统架构总览");
        assertThat(candidate.metadata().get("parentExpanded")).isEqualTo(true);

        // 契约断言：子块原始证据内容与身份保持绝对不可变
        assertThat(candidate.content()).isEqualTo("子分块具体实现代码细节。");
        assertThat(candidate.heading()).isEqualTo("详细组件实现");
    }

    @Test
    void testExpandViaParentChunkReader() {
        UUID projectId = UUID.randomUUID();
        UUID docId = UUID.randomUUID();
        UUID parentChunkId = UUID.randomUUID();

        String parentContent = "完整的父分块内容，覆盖该章节全部背景与推导过程。";
        DocumentSearchHit parentHit = new DocumentSearchHit(
                parentChunkId,
                docId,
                "spec.pdf",
                "第三章 算法推导",
                parentContent,
                "parentHash",
                1.0,
                Map.of("bodySnapshotId", "snapshot-A")
        );

        ParentChunkReader reader = (proj, id) -> {
            if (id.equals(parentChunkId)) return Optional.of(parentHit);
            return Optional.empty();
        };

        ParentChildExpander expander = new ParentChildExpander(reader, 1000);

        RetrievalCandidate child = new RetrievalCandidate(
                UUID.randomUUID(), docId, "spec.pdf", "3.2 推导步骤", "步骤一公式推导如下...", "childHash",
                Map.of("parentChunkId", parentChunkId.toString(), "bodySnapshotId", "snapshot-A"),
                1, 0.9, null, null, 0.04, null, null
        );

        List<RetrievalCandidate> res = expander.expand(projectId, List.of(child));

        assertThat(res).hasSize(1);
        RetrievalCandidate out = res.getFirst();

        assertThat(out.parentContext()).isEqualTo(parentContent);
        assertThat(out.metadata().get("parentHeading")).isEqualTo("第三章 算法推导");
        assertThat(out.metadata().get("parentExpanded")).isEqualTo(true);
    }

    @Test
    void testCrossDocumentRejected() {
        UUID projectId = UUID.randomUUID();
        UUID docA = UUID.randomUUID();
        UUID docB = UUID.randomUUID();
        UUID parentChunkId = UUID.randomUUID();

        // 父分块归属于 docB
        DocumentSearchHit parentHit = new DocumentSearchHit(
                parentChunkId,
                docB,
                "other.pdf",
                "其他文档",
                "父内容",
                "pHash",
                1.0,
                Map.of("bodySnapshotId", "snap1")
        );

        ParentChunkReader reader = (proj, id) -> Optional.of(parentHit);
        ParentChildExpander expander = new ParentChildExpander(reader);

        // 子分块归属于 docA
        RetrievalCandidate child = new RetrievalCandidate(
                UUID.randomUUID(), docA, "self.pdf", "当前文档", "子内容", "cHash",
                Map.of("parentChunkId", parentChunkId.toString(), "bodySnapshotId", "snap1"),
                1, 0.9, null, null, 0.04, null, null
        );

        List<RetrievalCandidate> res = expander.expand(projectId, List.of(child));

        assertThat(res).hasSize(1);
        RetrievalCandidate out = res.getFirst();

        // 严禁跨文档混读！
        assertThat(out.metadata()).containsEntry("parentExpansionRejected", "DOCUMENT_MISMATCH");
        assertThat(out.parentContext()).isNull();
    }

    @Test
    void testCrossVersionRejected() {
        UUID projectId = UUID.randomUUID();
        UUID docId = UUID.randomUUID();
        UUID parentChunkId = UUID.randomUUID();

        // 父分块属于版本 snap-OLD
        DocumentSearchHit parentHit = new DocumentSearchHit(
                parentChunkId,
                docId,
                "doc.pdf",
                "旧版本父块",
                "旧正文",
                "pHash",
                1.0,
                Map.of("bodySnapshotId", "snap-OLD")
        );

        ParentChunkReader reader = (proj, id) -> Optional.of(parentHit);
        ParentChildExpander expander = new ParentChildExpander(reader);

        // 子分块属于版本 snap-NEW
        RetrievalCandidate child = new RetrievalCandidate(
                UUID.randomUUID(), docId, "doc.pdf", "新版本子块", "新正文", "cHash",
                Map.of("parentChunkId", parentChunkId.toString(), "bodySnapshotId", "snap-NEW"),
                1, 0.9, null, null, 0.04, null, null
        );

        List<RetrievalCandidate> res = expander.expand(projectId, List.of(child));

        assertThat(res).hasSize(1);
        RetrievalCandidate out = res.getFirst();

        // 严禁跨版本混读！
        assertThat(out.metadata()).containsEntry("parentExpansionRejected", "VERSION_MISMATCH");
        assertThat(out.parentContext()).isNull();
    }
}
