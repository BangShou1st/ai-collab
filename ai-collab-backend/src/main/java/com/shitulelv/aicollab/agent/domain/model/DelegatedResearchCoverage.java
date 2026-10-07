package com.shitulelv.aicollab.agent.domain.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 委派子运行的研究覆盖事实：只由子运行持久化的工具结果推导，不由模型填写，
 * 也不追加任何统计用工具调用；随 {@code DELEGATION_COMPLETED} 一并回收，
 * 供父运行如实说明"实际已读/未读范围"。
 *
 * <p>真实 B-narrow2 实验暴露的转述边界：子运行已取得提纲并读完 4 节，
 * 父综合却两次声称"未取得提纲"——父运行只看得到子运行的文字结论，
 * 看不到子工具级覆盖事实。本类把覆盖事实做成可用事实，而不是让模型猜测。</p>
 *
 * <p>边界（与文档工具契约一致）：</p>
 * <ul>
 *   <li>检索命中（{@code RELEVANT_EXCERPTS_ONLY}）不等于正文读完，不计入已读章节；</li>
 *   <li>{@code HEURISTIC_HEADINGS} 提纲由标题识别得出，不是保证完整的目录；</li>
 *   <li>未取得提纲时未读范围未知，不得编造未读清单；</li>
 *   <li>旧回收记录没有 coverage 字段时按"未知"渲染，绝不解释成"未取得提纲"。</li>
 * </ul>
 *
 * <p>本类是纯函数（无数据库、无配置），提取与渲染都可在单测中直接验证。</p>
 */
public final class DelegatedResearchCoverage {

    public static final String OUTLINE_TOOL = "get_document_outline";
    public static final String READ_TOOL = "read_document_section";
    public static final String SEARCH_TOOL = "search_project_knowledge";

    public static final String OUTLINE_OBTAINED = "OBTAINED";
    public static final String OUTLINE_FAILED = "FAILED";
    public static final String OUTLINE_NOT_ATTEMPTED = "NOT_ATTEMPTED";

    /** structure 字段值：启发式标题识别。 */
    public static final String HEURISTIC_STRUCTURE = "HEURISTIC_HEADINGS";
    /** 与 Java 常量同名，供渲染判定可信度。 */
    public static final String TRUST_HEURISTIC = "HEURISTIC";

    private static final int MAX_DOCUMENTS = 20;
    private static final int MAX_HEADINGS = 20;
    private static final int MAX_GAPS = 20;
    private static final int MAX_LIMITS = 20;
    private static final int MAX_FAILURES = 10;

    /** 旧回收记录缺覆盖元数据时的统一渲染文本：未知，而不是"未取得提纲"。 */
    public static final String UNKNOWN_FACTS =
            "覆盖事实：未知（该回收记录没有结构化覆盖元数据；不得据此声称提纲未取得或未读任何章节，"
                    + "也不得把未知范围当成已读）。";

    /**
     * 一条持久化的工具结果（成功或失败），字段直接来自 {@code agent_step}：
     * {@code tool_name} / {@code reason}（TOOL_SUCCESS|TOOL_ERROR）/ {@code input_json} / {@code output_json}。
     */
    public record PersistedToolResult(String toolName, String reason, JsonNode input, JsonNode output) {}

    private DelegatedResearchCoverage() {}

    /**
     * 从子运行持久化工具结果提取覆盖事实。只统计成功工具结果里的文档身份、提纲、已读章节与检索命中；
     * 失败工具结果只如实记录失败，不推断文档内容。结束原因由调用方传入（子运行终态）。
     */
    public static ObjectNode extract(ObjectMapper json, List<PersistedToolResult> results, String endReason) {
        Map<String, DocumentState> documents = new LinkedHashMap<>();
        int searchCalls = 0;
        int searchExcerpts = 0;
        List<String> limits = new ArrayList<>();
        List<String> failures = new ArrayList<>();

        for (PersistedToolResult result : results == null ? List.<PersistedToolResult>of() : results) {
            if (result == null || result.toolName() == null) continue;
            String tool = result.toolName();
            JsonNode output = result.output();
            boolean success = "TOOL_SUCCESS".equals(result.reason())
                    && output != null && "SUCCEEDED".equals(output.path("status").asText("SUCCEEDED"));
            if (!success) {
                if (!OUTLINE_TOOL.equals(tool) && !READ_TOOL.equals(tool) && !SEARCH_TOOL.equals(tool)) continue;
                String documentId = argumentDocumentId(result.input());
                String errorCode = errorCode(output);
                failures.add(tool + (documentId == null ? "" : "(" + documentId + ")") + ": " + errorCode);
                if (OUTLINE_TOOL.equals(tool) && documentId != null) {
                    document(documents, documentId).outlineFailures++;
                }
                continue;
            }
            JsonNode data = output.path("data");
            if (OUTLINE_TOOL.equals(tool)) {
                DocumentState state = document(documents, text(data, "documentId"));
                state.observeIdentity(data);
                JsonNode sections = data.path("sections");
                state.outlineObtained = true;
                state.outlineStructure = text(data, "structure");
                if (sections.isArray()) {
                    state.outlineSectionsListed += sections.size();
                    for (JsonNode section : sections) {
                        String heading = text(section, "heading");
                        if (heading != null && !heading.isBlank()) state.outlineHeadings.add(heading);
                    }
                }
                if (data.path("truncated").asBoolean(false) && !state.outlineTruncated) {
                    state.outlineTruncated = true;
                    limits.add("get_document_outline：文档 " + state.documentId
                            + " 的提纲列表被截断（超出工具返回上限）");
                }
            } else if (READ_TOOL.equals(tool)) {
                DocumentState state = document(documents, text(data, "documentId"));
                state.observeIdentity(data);
                JsonNode items = data.path("items");
                if (items.isArray()) {
                    state.readChunks += items.size();
                    for (JsonNode item : items) {
                        String heading = text(item, "heading");
                        if (heading != null && !heading.isBlank()) state.readHeadings.add(heading);
                    }
                }
                if ((data.path("truncated").asBoolean(false) || data.path("hasMore").asBoolean(false))
                        && !state.readTruncated) {
                    state.readTruncated = true;
                    JsonNode continuation = data.path("continuation");
                    String fromChunk = continuation.isObject() && continuation.hasNonNull("fromChunk")
                            ? String.valueOf(continuation.path("fromChunk").asInt()) : null;
                    limits.add("read_document_section：文档 " + state.documentId + " 的正文读取被分页/截断"
                            + (fromChunk == null ? "" : "（续读点 fromChunk=" + fromChunk + "）"));
                }
            } else if (SEARCH_TOOL.equals(tool)) {
                searchCalls++;
                JsonNode sources = data.path("sources");
                if (sources.isArray()) searchExcerpts += sources.size();
            }
        }

        ObjectNode coverage = json.createObjectNode();
        coverage.put("coverageKnown", true);
        coverage.put("endReason", endReason == null || endReason.isBlank() ? "UNKNOWN" : endReason);

        ArrayNode documentsNode = coverage.putArray("documents");
        for (DocumentState state : documents.values()) {
            ObjectNode doc = documentsNode.addObject();
            doc.put("documentId", state.documentId);
            putNullable(doc, "snapshotId", state.snapshotId);
            putNullable(doc, "processingStatus", state.processingStatus);
            ObjectNode outline = doc.putObject("outline");
            outline.put("status", state.outlineObtained ? OUTLINE_OBTAINED
                    : state.outlineFailures > 0 ? OUTLINE_FAILED : OUTLINE_NOT_ATTEMPTED);
            if (state.outlineObtained) {
                outline.put("structure", state.outlineStructure);
                outline.put("trust", HEURISTIC_STRUCTURE.equals(state.outlineStructure)
                        ? TRUST_HEURISTIC : "UNKNOWN");
                outline.put("sectionsListed", state.outlineSectionsListed);
                outline.put("truncated", state.outlineTruncated);
            }
            outline.put("failures", state.outlineFailures);
            ObjectNode read = doc.putObject("sectionsRead");
            read.put("count", state.readChunks);
            ArrayNode headings = read.putArray("headings");
            state.readHeadings.stream().limit(MAX_HEADINGS).forEach(headings::add);
            read.put("truncated", state.readTruncated);
            read.put("unreadRangeUnknown", !state.outlineObtained);
        }

        if (searchCalls > 0) {
            ObjectNode search = coverage.putObject("search");
            search.put("calls", searchCalls);
            search.put("excerpts", searchExcerpts);
            search.put("coverage", "RELEVANT_EXCERPTS_ONLY");
            search.put("fullBodyRead", false);
        }

        List<String> gaps = deriveGaps(documents.values(), searchCalls, failures);
        ArrayNode gapsNode = coverage.putArray("gaps");
        gaps.stream().limit(MAX_GAPS).forEach(gapsNode::add);
        ArrayNode limitsNode = coverage.putArray("limits");
        limits.stream().limit(MAX_LIMITS).forEach(limitsNode::add);

        List<String> notes = new ArrayList<>();
        if (searchCalls > 0) {
            notes.add("检索返回的是相关摘录（RELEVANT_EXCERPTS_ONLY），不计入正文覆盖。");
        }
        if (documents.values().stream().anyMatch(
                state -> state.outlineObtained && HEURISTIC_STRUCTURE.equals(state.outlineStructure))) {
            notes.add("HEURISTIC_HEADINGS 提纲由标题识别得出，不是保证完整的目录；取得提纲不等于读完全文。");
        }
        ArrayNode notesNode = coverage.putArray("notes");
        notes.forEach(notesNode::add);

        return coverage;
    }

    private static List<String> deriveGaps(
            Iterable<DocumentState> documents, int searchCalls, List<String> failures) {
        List<String> gaps = new ArrayList<>();
        int documentCount = 0;
        for (DocumentState state : documents) {
            documentCount++;
            if (!state.outlineObtained) {
                if (state.outlineFailures > 0) {
                    gaps.add("文档 " + state.documentId + "：提纲工具调用失败（" + state.outlineFailures
                            + " 次），未取得提纲；未读范围未知，不得枚举未读章节");
                } else {
                    gaps.add("文档 " + state.documentId
                            + "：未取得提纲；未读范围未知，不得声称已按提纲读完，也不得枚举未读章节");
                }
                continue;
            }
            if (state.readChunks == 0) {
                gaps.add("文档 " + state.documentId + "：已取得提纲但未读取任何正文");
                continue;
            }
            if (!state.outlineHeadings.isEmpty()) {
                Set<String> listedRead = new LinkedHashSet<>(state.outlineHeadings);
                listedRead.retainAll(state.readHeadings);
                Set<String> unread = new LinkedHashSet<>(state.outlineHeadings);
                unread.removeAll(state.readHeadings);
                if (!unread.isEmpty()) {
                    gaps.add("文档 " + state.documentId + "：提纲列出 " + state.outlineHeadings.size()
                            + " 节，已读 " + listedRead.size() + " 节；未读："
                            + String.join("、", unread.stream().limit(MAX_HEADINGS).toList()));
                }
            }
            if (state.readTruncated) {
                gaps.add("文档 " + state.documentId + "：正文读取被截断，后续内容未读完");
            }
        }
        if (documentCount == 0 && searchCalls > 0) {
            gaps.add("仅有检索命中，未读取任何正文（检索命中不等于正文读完）");
        }
        if (documentCount == 0 && searchCalls == 0) {
            gaps.add("子运行没有成功的文档研究工具结果（无正文覆盖证据）");
        }
        if (!failures.isEmpty()) {
            gaps.add("工具失败：" + join(failures, MAX_FAILURES));
        }
        return gaps;
    }

    /**
     * 渲染给父运行的覆盖块文本。coverage 为 null / 非对象 / 标记覆盖未知时返回
     * {@link #UNKNOWN_FACTS}——旧回收记录按未知兼容，不解释成"未取得提纲"。
     */
    public static String renderForParentPrompt(JsonNode coverage) {
        if (coverage == null || !coverage.isObject() || !coverage.path("coverageKnown").asBoolean(true)) {
            return UNKNOWN_FACTS;
        }
        StringBuilder text = new StringBuilder("覆盖事实（由子运行持久化工具结果推导，已校验；不是子运行的文字转述）：\n");
        text.append("- 结束原因：").append(coverage.path("endReason").asText("UNKNOWN")).append('\n');
        JsonNode documents = coverage.path("documents");
        if (documents.isArray() && !documents.isEmpty()) {
            int index = 0;
            for (JsonNode doc : documents) {
                if (index++ >= MAX_DOCUMENTS) {
                    text.append("- （其余文档已省略）\n");
                    break;
                }
                appendDocument(text, doc);
            }
        } else {
            text.append("- 文档：无正文读取记录\n");
        }
        JsonNode search = coverage.path("search");
        if (search.isObject()) {
            text.append("- 检索：").append(search.path("calls").asInt()).append(" 次调用，返回 ")
                    .append(search.path("excerpts").asInt())
                    .append(" 条命中摘录（检索命中不等于正文读完）\n");
        }
        appendList(text, "分页/截断限制", coverage.path("limits"), "无");
        appendList(text, "已知覆盖缺口", coverage.path("gaps"), "无");
        appendList(text, "边界提醒", coverage.path("notes"), null);
        return text.toString();
    }

    private static void appendDocument(StringBuilder text, JsonNode doc) {
        text.append("- 文档 ").append(doc.path("documentId").asText("UNKNOWN"));
        List<String> identity = new ArrayList<>();
        if (doc.hasNonNull("snapshotId")) identity.add("snapshot " + doc.path("snapshotId").asText());
        if (doc.hasNonNull("processingStatus")) identity.add(doc.path("processingStatus").asText());
        if (!identity.isEmpty()) text.append("（").append(String.join("，", identity)).append("）");
        text.append('\n');
        JsonNode outline = doc.path("outline");
        String outlineStatus = outline.path("status").asText(OUTLINE_NOT_ATTEMPTED);
        if (OUTLINE_OBTAINED.equals(outlineStatus)) {
            String trust = TRUST_HEURISTIC.equals(outline.path("trust").asText())
                    ? "启发式标题识别，不代表完整目录" : outline.path("trust").asText("未知");
            text.append("  - 提纲：已取得（structure=").append(outline.path("structure").asText("UNKNOWN"))
                    .append("，可信度=").append(trust)
                    .append("，列出 ").append(outline.path("sectionsListed").asInt()).append(" 节")
                    .append(outline.path("truncated").asBoolean(false) ? "，列表被截断" : "，未截断")
                    .append("）\n");
        } else if (OUTLINE_FAILED.equals(outlineStatus)) {
            text.append("  - 提纲：未取得（提纲工具调用失败）\n");
        } else {
            text.append("  - 提纲：未取得（未调用提纲工具）\n");
        }
        JsonNode read = doc.path("sectionsRead");
        text.append("  - 正文：已读 ").append(read.path("count").asInt()).append(" 节");
        List<String> headings = new ArrayList<>();
        if (read.path("headings").isArray()) read.path("headings").forEach(heading -> headings.add(heading.asText()));
        if (!headings.isEmpty()) text.append("（").append(String.join("、", headings)).append("）");
        text.append(read.path("truncated").asBoolean(false) ? "，存在分页/截断未读完" : "，无分页截断").append('\n');
    }

    private static void appendList(StringBuilder text, String label, JsonNode values, String emptyText) {
        if (!values.isArray() || values.isEmpty()) {
            if (emptyText != null) text.append("- ").append(label).append('：').append(emptyText).append('\n');
            return;
        }
        for (JsonNode value : values) {
            text.append("- ").append(label).append('：').append(value.asText()).append('\n');
        }
    }

    private static DocumentState document(Map<String, DocumentState> documents, String documentId) {
        String key = documentId == null || documentId.isBlank() ? "UNKNOWN" : documentId;
        return documents.computeIfAbsent(key, DocumentState::new);
    }

    private static String argumentDocumentId(JsonNode input) {
        if (input == null) return null;
        JsonNode arguments = input.path("arguments");
        String documentId = text(arguments, "documentId");
        return documentId == null ? text(input, "documentId") : documentId;
    }

    private static String errorCode(JsonNode output) {
        if (output == null) return "UNKNOWN";
        JsonNode error = output.path("error");
        if (error.isObject() && error.hasNonNull("code")) return error.path("code").asText();
        if (error.isTextual() && !error.asText().isBlank()) return error.asText();
        return output.path("status").asText("FAILED");
    }

    private static String text(JsonNode node, String field) {
        if (node == null || !node.isObject()) return null;
        JsonNode value = node.path(field);
        return value.isTextual() && !value.asText().isBlank() ? value.asText() : null;
    }

    private static void putNullable(ObjectNode node, String field, String value) {
        if (value == null) node.putNull(field); else node.put(field, value);
    }

    private static String join(Iterable<String> values, int maximum) {
        List<String> limited = new ArrayList<>();
        for (String value : values) {
            if (limited.size() >= maximum) break;
            limited.add(value);
        }
        return String.join("；", limited);
    }

    private static final class DocumentState {
        private final String documentId;
        private String snapshotId;
        private String processingStatus;
        private boolean outlineObtained;
        private String outlineStructure;
        private int outlineSectionsListed;
        private boolean outlineTruncated;
        private int outlineFailures;
        private int readChunks;
        private boolean readTruncated;
        private final Set<String> outlineHeadings = new LinkedHashSet<>();
        private final Set<String> readHeadings = new LinkedHashSet<>();

        private DocumentState(String documentId) {
            this.documentId = documentId;
        }

        private void observeIdentity(JsonNode data) {
            if (snapshotId == null) snapshotId = text(data, "snapshotId");
            if (processingStatus == null) processingStatus = text(data, "processingStatus");
        }
    }
}
