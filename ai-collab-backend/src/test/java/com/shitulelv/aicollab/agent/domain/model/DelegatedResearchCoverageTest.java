package com.shitulelv.aicollab.agent.domain.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 委派研究覆盖事实（{@link DelegatedResearchCoverage}）单测：纯函数，无数据库。
 * 覆盖成功提纲、启发式提纲、完整/分页/截断读取、工具失败与旧记录兼容。
 */
class DelegatedResearchCoverageTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String DOCUMENT = "9fe67dbf-a013-4a53-a70f-138ba524ae64";
    private static final String SNAPSHOT = "2e6693d7-dbf7-47e1-8f4f-cab384c20f83";
    @Test
    void heuristicOutlineWithFullSectionReadsIsReportedAsObtained() {
        ObjectNode coverage = DelegatedResearchCoverage.extract(JSON, List.of(
                tool("get_document_outline", outline("架构概述", "评分算法", "与验收标准的对应关系", "性能设计")),
                read("架构概述"), read("评分算法"), read("与验收标准的对应关系"), read("性能设计")),
                "SUCCEEDED");

        assertThat(coverage.path("coverageKnown").asBoolean()).isTrue();
        assertThat(coverage.path("endReason").asText()).isEqualTo("SUCCEEDED");
        var doc = coverage.path("documents").get(0);
        assertThat(doc.path("documentId").asText()).isEqualTo(DOCUMENT);
        assertThat(doc.path("snapshotId").asText()).isEqualTo(SNAPSHOT);
        assertThat(doc.path("processingStatus").asText()).isEqualTo("READY");
        assertThat(doc.path("outline").path("status").asText()).isEqualTo("OBTAINED");
        assertThat(doc.path("outline").path("structure").asText()).isEqualTo("HEURISTIC_HEADINGS");
        assertThat(doc.path("outline").path("trust").asText()).isEqualTo("HEURISTIC");
        assertThat(doc.path("outline").path("sectionsListed").asInt()).isEqualTo(4);
        assertThat(doc.path("sectionsRead").path("count").asInt()).isEqualTo(4);
        assertThat(doc.path("sectionsRead").path("truncated").asBoolean()).isFalse();
        assertThat(coverage.path("gaps")).isEmpty();
        assertThat(coverage.path("limits")).isEmpty();
        // 启发式提纲不等于完整目录：固定边界提醒随覆盖事实一起给出
        assertThat(coverage.path("notes").toString()).contains("不是保证完整的目录");
    }

    @Test
    void searchHitsAreNotCountedAsBodyReads() {
        ObjectNode coverage = DelegatedResearchCoverage.extract(JSON, List.of(
                search(4)),
                "SUCCEEDED");

        assertThat(coverage.path("documents")).isEmpty();
        assertThat(coverage.path("search").path("calls").asInt()).isEqualTo(1);
        assertThat(coverage.path("search").path("excerpts").asInt()).isEqualTo(4);
        assertThat(coverage.path("search").path("fullBodyRead").asBoolean()).isFalse();
        assertThat(coverage.path("gaps").toString()).contains("仅有检索命中");
        assertThat(coverage.path("notes").toString()).contains("不计入正文覆盖");
    }

    @Test
    void paginatedReadIsReportedAsTruncated() {
        ObjectNode coverage = DelegatedResearchCoverage.extract(JSON, List.of(
                tool("get_document_outline", outline("第一章", "第二章")),
                truncatedRead("第一章", 3)),
                "BUDGET_EXCEEDED");

        var doc = coverage.path("documents").get(0);
        assertThat(doc.path("sectionsRead").path("truncated").asBoolean()).isTrue();
        assertThat(coverage.path("limits").toString()).contains("分页/截断").contains("fromChunk=3");
        assertThat(coverage.path("gaps").toString()).contains("后续内容未读完");
        assertThat(coverage.path("endReason").asText()).isEqualTo("BUDGET_EXCEEDED");
    }

    @Test
    void failedOutlineToolIsReportedAsFailedNotObtained() {
        ObjectNode coverage = DelegatedResearchCoverage.extract(JSON, List.of(
                failure("get_document_outline", "TOOL_EXECUTION_FAILED")),
                "FAILED");

        var doc = coverage.path("documents").get(0);
        assertThat(doc.path("outline").path("status").asText()).isEqualTo("FAILED");
        assertThat(doc.path("outline").path("failures").asInt()).isEqualTo(1);
        assertThat(coverage.path("gaps").toString())
                .contains("提纲工具调用失败")
                .contains("未读范围未知");
    }

    @Test
    void outlineTruncationIsRecordedAsLimit() {
        ObjectNode outline = outline("第一章", "第二章");
        ((ObjectNode) outline.path("data")).put("truncated", true);
        ObjectNode coverage = DelegatedResearchCoverage.extract(JSON, List.of(tool("get_document_outline", outline)),
                "SUCCEEDED");

        assertThat(coverage.path("documents").get(0).path("outline").path("truncated").asBoolean()).isTrue();
        assertThat(coverage.path("limits").toString()).contains("提纲列表被截断");
    }

    @Test
    void unreadChaptersAreListedOnlyWhenOutlineWasObtained() {
        // 提纲已取得：未读章节可由真实数据推出，允许列出
        ObjectNode withOutline = DelegatedResearchCoverage.extract(JSON, List.of(
                tool("get_document_outline", outline("第一章", "第二章", "第三章")),
                read("第一章")),
                "SUCCEEDED");
        assertThat(withOutline.path("gaps").toString())
                .contains("提纲列出 3 节")
                .contains("未读：第二章、第三章");

        // 未取得提纲：未读范围未知，不得编造未读清单
        ObjectNode withoutOutline = DelegatedResearchCoverage.extract(JSON, List.of(read("第一章")),
                "SUCCEEDED");
        String gaps = withoutOutline.path("gaps").toString();
        assertThat(gaps).contains("未读范围未知").doesNotContain("未读：");
        assertThat(withoutOutline.path("documents").get(0).path("sectionsRead")
                .path("unreadRangeUnknown").asBoolean()).isTrue();
    }

    @Test
    void legacyRecordWithoutCoverageRendersUnknownInsteadOfMissingOutline() {
        assertThat(DelegatedResearchCoverage.renderForParentPrompt(null))
                .contains("覆盖事实：未知")
                .doesNotContain("未取得提纲");
        // 旧记录读取 DELEGATION_COMPLETED 时 coverage 字段缺失 → MissingNode
        ObjectNode legacyStep = JSON.createObjectNode().put("childRunId", "child-1").put("status", "SUCCEEDED");
        assertThat(DelegatedResearchCoverage.renderForParentPrompt(legacyStep.path("coverage")))
                .isEqualTo(DelegatedResearchCoverage.UNKNOWN_FACTS);
        // 显式标记覆盖未知同样按未知处理
        ObjectNode unknown = JSON.createObjectNode().put("coverageKnown", false);
        assertThat(DelegatedResearchCoverage.renderForParentPrompt(unknown))
                .isEqualTo(DelegatedResearchCoverage.UNKNOWN_FACTS);
    }

    @Test
    void renderStatesVerifiedOutlineFacts() {
        ObjectNode coverage = DelegatedResearchCoverage.extract(JSON, List.of(
                tool("get_document_outline", outline("架构概述", "评分算法")),
                read("架构概述"), read("评分算法")),
                "SUCCEEDED");

        String rendered = DelegatedResearchCoverage.renderForParentPrompt(coverage);
        assertThat(rendered)
                .contains("已校验")
                .contains(DOCUMENT)
                .contains("提纲：已取得")
                .contains("列出 2 节")
                .contains("已读 2 节")
                .contains("已知覆盖缺口：无");
    }

    @Test
    void childWithNoResearchToolResultsReportsHonestEmptyCoverage() {
        ObjectNode coverage = DelegatedResearchCoverage.extract(JSON, List.of(), "SUCCEEDED");
        assertThat(coverage.path("coverageKnown").asBoolean()).isTrue();
        assertThat(coverage.path("gaps").toString()).contains("没有成功的文档研究工具结果");
        assertThat(DelegatedResearchCoverage.renderForParentPrompt(coverage))
                .contains("无正文读取记录")
                .doesNotContain("未取得提纲");
    }

    // ===== fixtures =====

    // ==================================================================
    // F3 回归：覆盖投影必须忠于工具返回的 chunk/offset/continuation 事实
    // ==================================================================

    /**
     * F3-1 回归：同一标题下的多个 body chunk 是<b>片段</b>，不是多个章节。
     * 修复前：`items.size()` 被当作章节数，一个标题两个 chunk 会报"已读 2 节"。
     */
    @Test
    void f3MultipleChunksUnderOneHeadingCountAsOneSectionNotManySections() {
        ObjectNode coverage = DelegatedResearchCoverage.extract(JSON, List.of(
                tool("get_document_outline", outline("评分算法")),
                // 同一标题的两段连续 chunk，都从各自起点读
                readChunk("评分算法", 4, 0),
                readChunk("评分算法", 5, 0)),
                "SUCCEEDED");

        var read = coverage.path("documents").get(0).path("sectionsRead");
        // 章节数是去重标题数，不是片段数
        assertThat(read.path("count").asInt()).isEqualTo(1);
        // 片段数单独如实给出，两个语义不混用
        assertThat(read.path("fragmentCount").asInt()).isEqualTo(2);
        assertThat(coverage.path("gaps").toString()).doesNotContain("已读 2 节");
        assertThat(DelegatedResearchCoverage.renderForParentPrompt(coverage))
                .contains("已读 1 节")
                .contains("实际读取 2 个正文片段");
    }

    /**
     * F3-2 回归：分页读取经 continuation 续到结尾后，续读链闭合，
     * 不再永久声明"后续内容未读完"。
     * 修复前：`readTruncated` 只置 true 且从不按已闭合续读更新。
     */
    @Test
    void f3ClosedContinuationChainStopsReportingUnreadRemainder() {
        ObjectNode coverage = DelegatedResearchCoverage.extract(JSON, List.of(
                tool("get_document_outline", outline("第一章")),
                // 第一页：在第 3 个 chunk 处截断，声明续读点 fromChunk=3
                readChunk("第一章", 0, 0, true, 3, 0),
                // 按 continuation 续读，这次读到结尾（hasMore=false）
                readChunk("第一章", 3, 0, false, null, 0)),
                "SUCCEEDED");

        var read = coverage.path("documents").get(0).path("sectionsRead");
        // 续读已闭合：不得再声明未读完
        assertThat(read.path("truncated").asBoolean()).isFalse();
        assertThat(coverage.path("gaps").toString()).doesNotContain("后续内容未读完");
        assertThat(DelegatedResearchCoverage.renderForParentPrompt(coverage))
                .contains("无未闭合的分页截断");
    }

    /**
     * F3-2 反向回归：续读链<b>未</b>闭合并仍然敞开时，必须如实保留"未读完"。
     * 闭合判定不能宽松到把真正未读完的分页读取说成读完。
     */
    @Test
    void f3OpenContinuationChainStillReportsUnreadRemainder() {
        ObjectNode coverage = DelegatedResearchCoverage.extract(JSON, List.of(
                tool("get_document_outline", outline("第一章", "第二章")),
                readChunk("第一章", 0, 0, true, 3, 0)),
                "SUCCEEDED");

        assertThat(coverage.path("documents").get(0).path("sectionsRead").path("truncated").asBoolean()).isTrue();
        assertThat(coverage.path("gaps").toString()).contains("后续内容未读完");
        assertThat(coverage.path("limits").toString()).contains("fromChunk=3");
    }

    /**
     * F3-3 回归：从章节中间 chunk/offset 读后缀、到结尾 hasMore=false 时，
     * 未覆盖的前缀必须记为缺口，该标题不能算作已读。
     * 修复前：gaps 为空、该标题被列为已读，未读前缀静默消失。
     */
    @Test
    void f3SuffixOnlyReadDoesNotClaimTheHeadingWasRead() {
        ObjectNode coverage = DelegatedResearchCoverage.extract(JSON, List.of(
                tool("get_document_outline", outline("评分算法")),
                // 从 chunk 7 的 offset 900 开始读后缀，一直读到结尾
                readChunk("评分算法", 7, 900, false, null, 0)),
                "SUCCEEDED");

        var read = coverage.path("documents").get(0).path("sectionsRead");
        // 只读后缀：不能算已读该节
        assertThat(read.path("count").asInt()).isZero();
        assertThat(read.path("partiallyReadHeadings").toString()).contains("评分算法");
        String gaps = coverage.path("gaps").toString();
        assertThat(gaps)
                .contains("起始前缀未被覆盖")
                .contains("仅读取了后缀");
        // 报告必须如实说明该标题未读全，而不是把它当作已读
        assertThat(DelegatedResearchCoverage.renderForParentPrompt(coverage))
                .contains("仅读后缀的标题");
    }

    /**
     * F3-4 回归：同一提纲读取两次，列出节数不得翻倍。
     * 修复前：累加调用返回条数（sectionsListed 翻倍），把重复调用当成更多章节。
     */
    @Test
    void f3RepeatedOutlineReadDoesNotDoubleTheListedSections() {
        ObjectNode coverage = DelegatedResearchCoverage.extract(JSON, List.of(
                tool("get_document_outline", outline("第一章", "第二章")),
                tool("get_document_outline", outline("第一章", "第二章")),
                read("第一章"), read("第二章")),
                "SUCCEEDED");

        var outline = coverage.path("documents").get(0).path("outline");
        assertThat(outline.path("sectionsListed").asInt()).isEqualTo(2);
        assertThat(coverage.path("gaps").toString()).doesNotContain("提纲列出 4 节");
        assertThat(DelegatedResearchCoverage.renderForParentPrompt(coverage)).contains("列出 2 节");
    }

    // ==================================================================
    // F4 回归：同一文档的不同快照不得合并成"已校验完整版本"
    // ==================================================================

    /**
     * F4 回归：先取旧快照提纲、后读新快照正文时，两个版本不能合并，
     * 也不能把新快照正文挂到旧快照上。
     * 修复前：状态按 documentId 单独建键、snapshotId 只取第一次非空值，
     * 结果只保留旧快照却把新快照正文计入其覆盖。
     */
    @Test
    void f4DifferentSnapshotsAreNotMergedIntoVerifiedCoverage() {
        String oldSnapshot = "11111111-1111-1111-1111-111111111111";
        String newSnapshot = "22222222-2222-2222-2222-222222222222";
        ObjectNode coverage = DelegatedResearchCoverage.extract(JSON, List.of(
                outlineWithSnapshot(oldSnapshot, "旧章节 A"),
                readWithSnapshot(newSnapshot, "新章节 B", 0, 0, false, null)),
                "SUCCEEDED");

        // 两个快照各自成条，不合并
        assertThat(coverage.path("documents")).hasSize(2);
        List<String> snapshots = new ArrayList<>();
        coverage.path("documents").forEach(doc -> snapshots.add(doc.path("snapshotId").asText()));
        assertThat(snapshots).containsExactlyInAnyOrder(oldSnapshot, newSnapshot);

        // 旧快照只有提纲、没有正文；新快照只有正文、没有提纲
        for (var doc : coverage.path("documents")) {
            String snapshot = doc.path("snapshotId").asText();
            var read = doc.path("sectionsRead");
            if (oldSnapshot.equals(snapshot)) {
                assertThat(read.path("count").asInt())
                        .as("旧快照不得把新快照的正文计入自己的覆盖")
                        .isZero();
            } else {
                assertThat(doc.path("outline").path("status").asText()).isEqualTo("NOT_ATTEMPTED");
            }
        }

        // 版本冲突必须显式报告，而不是拼成一个"已校验完整版本"
        assertThat(coverage.path("gaps").toString())
                .contains("研究期间出现 2 个不同快照")
                .contains("不合并陈述");
    }

    /**
     * F4 兼容回归：快照未知时不与其他未知快照合并，也不推断与已知快照同版本。
     */
    @Test
    void f4UnknownSnapshotIsNotGuessedToBeTheSameVersion() {
        String knownSnapshot = "33333333-3333-3333-3333-333333333333";
        ObjectNode coverage = DelegatedResearchCoverage.extract(JSON, List.of(
                outlineWithSnapshot(knownSnapshot, "章节 A"),
                // 未传 snapshotId 的读取：身份未知
                readWithSnapshot(null, "章节 A", 0, 0, false, null)),
                "SUCCEEDED");

        assertThat(coverage.path("documents")).hasSize(2);
        // 未知快照条目如实标 null，不被当作已知版本
        boolean hasUnknown = false;
        for (var doc : coverage.path("documents")) {
            if (doc.path("snapshotId").isNull()) hasUnknown = true;
        }
        assertThat(hasUnknown).isTrue();
    }

    private static DelegatedResearchCoverage.PersistedToolResult tool(String name, ObjectNode output) {
        return new DelegatedResearchCoverage.PersistedToolResult(name, "TOOL_SUCCESS",
                JSON.createObjectNode().put("toolCallId", name + "-call"), output);
    }

    /** 读取一个指定 chunk 起点的正文片段（可声明续读点/是否到结尾）。 */
    private static DelegatedResearchCoverage.PersistedToolResult readChunk(
            String heading, int chunkNo, int fromOffset) {
        return readChunk(heading, chunkNo, fromOffset, false, null, 0);
    }

    private static DelegatedResearchCoverage.PersistedToolResult readChunk(
            String heading, int chunkNo, int fromOffset,
            boolean hasMore, Integer continuationChunk, int continuationOffset) {
        return new DelegatedResearchCoverage.PersistedToolResult("read_document_section", "TOOL_SUCCESS",
                JSON.createObjectNode().put("toolCallId", "read-" + chunkNo),
                readOutput(heading, chunkNo, fromOffset, hasMore, continuationChunk, continuationOffset));
    }

    private static ObjectNode readOutput(String heading, int chunkNo, int fromOffset,
            boolean hasMore, Integer continuationChunk, int continuationOffset) {
        ObjectNode data = JSON.createObjectNode()
                .put("documentId", DOCUMENT).put("snapshotId", SNAPSHOT)
                .put("processingStatus", "READY").put("readChars", 120)
                .put("coverage", "SPECIFIED_RANGE_ONLY")
                .put("truncated", hasMore).put("hasMore", hasMore);
        data.putArray("items").addObject().put("chunkId", heading + "-chunk-" + chunkNo)
                .put("chunkNo", chunkNo).put("heading", heading).put("content", "...")
                .put("fromOffset", fromOffset).put("throughOffset", fromOffset + 120);
        if (hasMore && continuationChunk != null) {
            data.putObject("continuation").put("fromChunk", continuationChunk)
                    .put("fromOffset", continuationOffset).put("snapshotId", SNAPSHOT);
        } else {
            data.putNull("continuation");
        }
        return JSON.createObjectNode().put("status", "SUCCEEDED").set("data", data);
    }

    /** 指定快照的提纲输出。 */
    private static DelegatedResearchCoverage.PersistedToolResult outlineWithSnapshot(
            String snapshotId, String... headings) {
        ObjectNode data = JSON.createObjectNode()
                .put("documentId", DOCUMENT).put("snapshotId", snapshotId)
                .put("processingStatus", "READY").put("structure", "HEURISTIC_HEADINGS").put("truncated", false);
        var sections = data.putArray("sections");
        for (int index = 0; index < headings.length; index++) {
            sections.addObject().put("heading", headings[index])
                    .put("from_chunk", index).put("through_chunk", index);
        }
        return tool("get_document_outline",
                JSON.createObjectNode().put("status", "SUCCEEDED").set("data", data));
    }

    /** 指定快照的正文读取输出。 */
    private static DelegatedResearchCoverage.PersistedToolResult readWithSnapshot(
            String snapshotId, String heading, int chunkNo, int fromOffset,
            boolean hasMore, Integer continuationChunk) {
        ObjectNode data = JSON.createObjectNode()
                .put("documentId", DOCUMENT)
                .put("processingStatus", "READY").put("readChars", 120)
                .put("coverage", "SPECIFIED_RANGE_ONLY")
                .put("truncated", hasMore).put("hasMore", hasMore);
        if (snapshotId == null) data.putNull("snapshotId"); else data.put("snapshotId", snapshotId);
        data.putArray("items").addObject().put("chunkId", heading + "-chunk")
                .put("chunkNo", chunkNo).put("heading", heading).put("content", "...")
                .put("fromOffset", fromOffset).put("throughOffset", fromOffset + 120);
        if (hasMore && continuationChunk != null) {
            data.putObject("continuation").put("fromChunk", continuationChunk).put("fromOffset", 0);
        } else {
            data.putNull("continuation");
        }
        return tool("read_document_section", JSON.createObjectNode().put("status", "SUCCEEDED").set("data", data));
    }

    private static DelegatedResearchCoverage.PersistedToolResult failure(String name, String errorCode) {
        ObjectNode input = JSON.createObjectNode().put("toolCallId", name + "-call");
        input.putObject("arguments").put("documentId", DOCUMENT);
        return new DelegatedResearchCoverage.PersistedToolResult(name, "TOOL_ERROR", input,
                JSON.createObjectNode().put("status", "FAILED").put("error", errorCode).put("message", "工具执行失败"));
    }

    private static ObjectNode outline(String... headings) {
        ObjectNode data = JSON.createObjectNode()
                .put("documentId", DOCUMENT).put("snapshotId", SNAPSHOT)
                .put("processingStatus", "READY").put("structure", "HEURISTIC_HEADINGS").put("truncated", false);
        var sections = data.putArray("sections");
        for (int index = 0; index < headings.length; index++) {
            sections.addObject().put("heading", headings[index])
                    .put("from_chunk", index).put("through_chunk", index);
        }
        return JSON.createObjectNode().put("status", "SUCCEEDED").set("data", data);
    }

    private static DelegatedResearchCoverage.PersistedToolResult read(String heading) {
        return tool("read_document_section", readOutput(heading, false, null));
    }

    private static DelegatedResearchCoverage.PersistedToolResult truncatedRead(String heading, int fromChunk) {
        return tool("read_document_section", readOutput(heading, true, fromChunk));
    }

    private static ObjectNode readOutput(String heading, boolean truncated, Integer fromChunk) {
        ObjectNode data = JSON.createObjectNode()
                .put("documentId", DOCUMENT).put("snapshotId", SNAPSHOT)
                .put("processingStatus", "READY").put("readChars", 120)
                .put("coverage", "SPECIFIED_RANGE_ONLY")
                .put("truncated", truncated).put("hasMore", truncated);
        data.putArray("items").addObject().put("chunkId", heading + "-chunk")
                .put("chunkNo", 0).put("heading", heading).put("content", "..." );
        if (fromChunk != null) data.putObject("continuation").put("fromChunk", fromChunk).put("fromOffset", 0);
        else data.putNull("continuation");
        return JSON.createObjectNode().put("status", "SUCCEEDED").set("data", data);
    }

    private static DelegatedResearchCoverage.PersistedToolResult search(int excerpts) {
        ObjectNode data = JSON.createObjectNode().put("coverage", "RELEVANT_EXCERPTS_ONLY").put("fullDocumentRead", false);
        var sources = data.putArray("sources");
        for (int index = 0; index < excerpts; index++) {
            sources.addObject().put("documentId", DOCUMENT).put("chunkId", "hit-" + index).put("quote", "...");
        }
        return tool("search_project_knowledge", JSON.createObjectNode().put("status", "SUCCEEDED").set("data", data));
    }
}
