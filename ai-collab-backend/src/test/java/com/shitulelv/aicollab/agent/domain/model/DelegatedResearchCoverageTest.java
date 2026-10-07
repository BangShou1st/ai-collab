package com.shitulelv.aicollab.agent.domain.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

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

    private static DelegatedResearchCoverage.PersistedToolResult tool(String name, ObjectNode output) {
        return new DelegatedResearchCoverage.PersistedToolResult(name, "TOOL_SUCCESS",
                JSON.createObjectNode().put("toolCallId", name + "-call"), output);
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
