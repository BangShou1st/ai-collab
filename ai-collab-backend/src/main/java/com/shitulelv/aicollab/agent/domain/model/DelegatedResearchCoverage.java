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
 * <p><b>事实精度（F3/F4 修复）：</b>本类被标注为"已校验事实"注入父提示词，
 * 提示词要求冲突时以它为准，因此投影必须严格忠于工具结果：</p>
 * <ul>
 *   <li><b>章节 ≠ 片段</b>：同一标题下的多个 body chunk 是片段，不是多个章节；
 *       已读章节数按去重标题计，不按 {@code items.size()} 计。</li>
 *   <li><b>续读可以闭合</b>：分页读取按 {@code continuation} 续到结尾
 *       （{@code hasMore=false}）后，不再声明"后续内容未读完"。</li>
 *   <li><b>半截读取不是读全</b>：从 chunk/offset 中间读取的后缀不能把该标题算作已读；
 *       未覆盖的前缀记为缺口，无法证明时保守标未知。</li>
 *   <li><b>重复提纲不翻倍</b>：同一提纲读两次记录同一份去重后的提纲事实。</li>
 *   <li><b>不跨快照合并</b>：同一文档的不同 {@code snapshotId} 分开陈述，
 *       并把版本冲突显式报告为缺口，而不是拼成一个"已校验完整版本"。</li>
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

    /** 快照身份未知时使用的内部键（不与其他未知快照合并，也不假装是同一版本）。 */
    private static final String UNKNOWN_SNAPSHOT = "UNKNOWN";

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
                    // 提纲失败发生在（文档, 快照）维度；失败时通常没有快照身份，
                    // 归入该文档的未知快照状态，不与已知快照合并。
                    snapshot(documents, documentId, null).outlineFailures++;
                }
                continue;
            }
            JsonNode data = output.path("data");
            if (OUTLINE_TOOL.equals(tool)) {
                SnapshotState state = snapshot(documents, text(data, "documentId"), text(data, "snapshotId"));
                state.observeProcessingStatus(text(data, "processingStatus"));
                state.outlineObtained = true;
                state.outlineStructure = text(data, "structure");
                JsonNode sections = data.path("sections");
                if (sections.isArray()) {
                    // F3-4：记录去重后的提纲事实，而不是累加调用返回条数。
                    // 同一提纲读两次必须仍然只陈述同一份提纲。
                    Set<String> callHeadings = new LinkedHashSet<>();
                    int callBlankHeadings = 0;
                    for (JsonNode section : sections) {
                        String heading = text(section, "heading");
                        if (heading != null && !heading.isBlank()) callHeadings.add(heading);
                        else callBlankHeadings++;
                    }
                    state.outlineHeadings.addAll(callHeadings);
                    state.outlineBlankSections += callBlankHeadings;
                }
                if (data.path("truncated").asBoolean(false) && !state.outlineTruncated) {
                    state.outlineTruncated = true;
                    limits.add(OUTLINE_TOOL + "：文档 " + state.documentId
                            + " 的提纲列表被截断（超出工具返回上限）");
                }
            } else if (READ_TOOL.equals(tool)) {
                SnapshotState state = snapshot(documents, text(data, "documentId"), text(data, "snapshotId"));
                state.observeProcessingStatus(text(data, "processingStatus"));
                JsonNode items = data.path("items");
                boolean hasMore = data.path("truncated").asBoolean(false)
                        || data.path("hasMore").asBoolean(false);
                if (items.isArray()) {
                    boolean firstItem = true;
                    for (JsonNode item : items) {
                        state.readItems++;
                        int chunkNo = item.path("chunkNo").asInt(0);
                        int fromOffset = item.path("fromOffset").asInt(0);
                        state.readRanges.add(new int[]{chunkNo, fromOffset});
                        String heading = text(item, "heading");
                        if (heading != null && !heading.isBlank()) {
                            // F3-1/F3-3：标题是否"读全"取决于证据，而不是出现次数。
                            // 只有从该标题起点开始读（fromOffset==0）才能算作已读该节；
                            // 从中间读到的后缀只标记为部分读取，并保留未覆盖前缀的缺口。
                            // 同一标题同时有前缀与后缀读取时，已读优先（并集语义）。
                            if (fromOffset == 0) {
                                state.fullHeadings.add(heading);
                                state.partialHeadings.remove(heading);
                            } else if (!state.fullHeadings.contains(heading)) {
                                state.partialHeadings.add(heading);
                            }
                        }
                        // F3-2：续读链的闭合判定。本次读取的首个条目正好从上次 continuation
                        // 声明的位置开始时，该续读点已被满足。
                        if (firstItem) {
                            state.consumeContinuation(chunkNo, fromOffset);
                            firstItem = false;
                        }
                    }
                }
                if (hasMore && !state.readTruncated) {
                    state.readTruncated = true;
                    JsonNode continuation = data.path("continuation");
                    String fromChunk = continuation.isObject() && continuation.hasNonNull("fromChunk")
                            ? String.valueOf(continuation.path("fromChunk").asInt()) : null;
                    limits.add(READ_TOOL + "：文档 " + state.documentId + " 的正文读取被分页/截断"
                            + (fromChunk == null ? "" : "（续读点 fromChunk=" + fromChunk + "）"));
                }
                if (hasMore) {
                    // 记录待续读点；下一次匹配的读取会消费它并可能闭合整条续读链
                    JsonNode continuation = data.path("continuation");
                    if (continuation.isObject() && continuation.hasNonNull("fromChunk")) {
                        state.pendingContinuationChunk = continuation.path("fromChunk").asInt();
                        state.pendingContinuationOffset = continuation.path("fromOffset").asInt(0);
                    } else {
                        state.pendingContinuationUnknown = true;
                    }
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
        List<String> snapshotConflicts = new ArrayList<>();
        for (DocumentState document : documents.values()) {
            // F4：同一文档的多个快照分开陈述，不合并成一个"已校验完整版本"。
            if (document.snapshots.size() > 1) {
                snapshotConflicts.add("文档 " + document.documentId + "：研究期间出现 " + document.snapshots.size()
                        + " 个不同快照（" + String.join("、", document.snapshots.keySet())
                        + "）；不同版本的提纲与正文不合并陈述，各自覆盖范围独立成立");
            }
            for (SnapshotState state : document.snapshots.values()) {
                documentsNode.add(writeDocument(json, document.documentId, state));
            }
        }

        if (searchCalls > 0) {
            ObjectNode search = coverage.putObject("search");
            search.put("calls", searchCalls);
            search.put("excerpts", searchExcerpts);
            search.put("coverage", "RELEVANT_EXCERPTS_ONLY");
            search.put("fullBodyRead", false);
        }

        List<String> gaps = deriveGaps(documents.values(), searchCalls, failures);
        gaps.addAll(snapshotConflicts);
        ArrayNode gapsNode = coverage.putArray("gaps");
        gaps.stream().limit(MAX_GAPS).forEach(gapsNode::add);
        ArrayNode limitsNode = coverage.putArray("limits");
        limits.stream().limit(MAX_LIMITS).forEach(limitsNode::add);

        List<String> notes = new ArrayList<>();
        if (searchCalls > 0) {
            notes.add("检索返回的是相关摘录（RELEVANT_EXCERPTS_ONLY），不计入正文覆盖。");
        }
        if (documents.values().stream().flatMap(document -> document.snapshots.values().stream())
                .anyMatch(state -> state.outlineObtained && HEURISTIC_STRUCTURE.equals(state.outlineStructure))) {
            notes.add("HEURISTIC_HEADINGS 提纲由标题识别得出，不是保证完整的目录；取得提纲不等于读完全文。");
        }
        if (documents.values().stream().flatMap(document -> document.snapshots.values().stream())
                .anyMatch(state -> !state.partialHeadings.isEmpty())) {
            notes.add("从 chunk/offset 中间读取的标题只能证明其后缀已读，其未覆盖前缀不计入已读范围。");
        }
        ArrayNode notesNode = coverage.putArray("notes");
        notes.forEach(notesNode::add);

        return coverage;
    }

    /** 写出一个（文档, 快照）覆盖陈述。 */
    private static ObjectNode writeDocument(ObjectMapper json, String documentId, SnapshotState state) {
        ObjectNode doc = json.createObjectNode();
        doc.put("documentId", documentId);
        putNullable(doc, "snapshotId", state.snapshotId);
        putNullable(doc, "processingStatus", state.processingStatus);
        ObjectNode outline = doc.putObject("outline");
        outline.put("status", state.outlineObtained ? OUTLINE_OBTAINED
                : state.outlineFailures > 0 ? OUTLINE_FAILED : OUTLINE_NOT_ATTEMPTED);
        if (state.outlineObtained) {
            outline.put("structure", state.outlineStructure);
            outline.put("trust", HEURISTIC_STRUCTURE.equals(state.outlineStructure)
                    ? TRUST_HEURISTIC : "UNKNOWN");
            // 去重后的提纲事实（重复读取同一提纲不翻倍；无标题的条目另计）
            outline.put("sectionsListed", state.outlineHeadings.size() + state.outlineBlankSections);
            outline.put("truncated", state.outlineTruncated);
        }
        outline.put("failures", state.outlineFailures);
        ObjectNode read = doc.putObject("sectionsRead");
        // F3-1：按去重标题计"节"，不按片段数计
        read.put("count", state.fullHeadings.size());
        read.put("fragmentCount", state.readItems);
        ArrayNode headings = read.putArray("headings");
        state.fullHeadings.stream().limit(MAX_HEADINGS).forEach(headings::add);
        ArrayNode partial = read.putArray("partiallyReadHeadings");
        state.partialHeadings.stream().limit(MAX_HEADINGS).forEach(partial::add);
        // F3-2：续读链闭合后不再声明有未读完内容
        boolean effectiveTruncated = state.pendingContinuationChunk != null || state.pendingContinuationUnknown;
        read.put("truncated", effectiveTruncated);
        read.put("unreadRangeUnknown", !state.outlineObtained);
        return doc;
    }

    private static List<String> deriveGaps(
            Iterable<DocumentState> documents, int searchCalls, List<String> failures) {
        List<String> gaps = new ArrayList<>();
        int documentCount = 0;
        for (DocumentState document : documents) {
            for (SnapshotState state : document.snapshots.values()) {
                documentCount++;
                String label = "文档 " + state.documentId
                        + (state.snapshotId == null ? "" : "（快照 " + state.snapshotId + "）");
                if (!state.outlineObtained) {
                    if (state.outlineFailures > 0) {
                        gaps.add(label + "：提纲工具调用失败（" + state.outlineFailures
                                + " 次），未取得提纲；未读范围未知，不得枚举未读章节");
                    } else {
                        gaps.add(label
                                + "：未取得提纲；未读范围未知，不得声称已按提纲读完，也不得枚举未读章节");
                    }
                    continue;
                }
                if (state.readItems == 0) {
                    gaps.add(label + "：已取得提纲但未读取任何正文");
                    continue;
                }
                // F3-3：未覆盖前缀必须如实报告，不能因为"最后 hasMore=false"就当作读全
                if (state.skippedDocumentPrefix()) {
                    gaps.add(label + "：正文从未读范围中间开始读取，起始前缀未被覆盖"
                            + (state.readRanges.isEmpty() ? ""
                                    : "（实际读取起始 chunk=" + state.minReadChunk() + "）"));
                }
                if (!state.partialHeadings.isEmpty()) {
                    gaps.add(label + "：以下标题仅读取了后缀（未覆盖前缀），不能算作已读该节："
                            + String.join("、", state.partialHeadings.stream().limit(MAX_HEADINGS).toList()));
                }
                if (!state.outlineHeadings.isEmpty()) {
                    Set<String> listedRead = new LinkedHashSet<>(state.outlineHeadings);
                    listedRead.retainAll(state.fullHeadings);
                    Set<String> unread = new LinkedHashSet<>(state.outlineHeadings);
                    unread.removeAll(state.fullHeadings);
                    if (!unread.isEmpty()) {
                        gaps.add(label + "：提纲列出 " + state.outlineHeadings.size()
                                + " 节，已读 " + listedRead.size() + " 节；未读："
                                + String.join("、", unread.stream().limit(MAX_HEADINGS).toList()));
                    }
                }
                if (state.pendingContinuationChunk != null || state.pendingContinuationUnknown) {
                    gaps.add(label + "：正文读取被截断，后续内容未读完"
                            + (state.pendingContinuationChunk == null ? ""
                                    : "（待续读 fromChunk=" + state.pendingContinuationChunk + "）"));
                }
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
        int sections = read.path("count").asInt();
        int fragments = read.path("fragmentCount").asInt(fragmentsFallback(sections));
        text.append("  - 正文：已读 ").append(sections).append(" 节");
        List<String> headings = new ArrayList<>();
        if (read.path("headings").isArray()) read.path("headings").forEach(heading -> headings.add(heading.asText()));
        if (!headings.isEmpty()) text.append("（").append(String.join("、", headings)).append("）");
        // 片段数与章节数是不同语义：明确说明，避免把分页片段误读成章节数量
        if (fragments > 0 && fragments != sections) {
            text.append("，实际读取 ").append(fragments).append(" 个正文片段");
        }
        List<String> partial = new ArrayList<>();
        if (read.path("partiallyReadHeadings").isArray()) {
            read.path("partiallyReadHeadings").forEach(heading -> partial.add(heading.asText()));
        }
        if (!partial.isEmpty()) {
            text.append("；另有仅读后缀的标题（未覆盖前缀，不计入已读）：")
                    .append(String.join("、", partial));
        }
        text.append(read.path("truncated").asBoolean(false) ? "，存在分页/截断未读完" : "，无未闭合的分页截断")
                .append('\n');
    }

    /** 旧记录没有 fragmentCount 字段时按已读节数回退，不虚构更小的数字。 */
    private static int fragmentsFallback(int sections) {
        return sections;
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

    /**
     * 取（文档, 快照）状态。F4：状态按文档<b>与快照</b>建键——旧实现只按 documentId
     * 建键且 snapshotId 只取第一次非空值，导致先取旧快照提纲、后读新快照正文时两者被
     * 合在一起、并挂到旧快照上，把跨版本结果陈述成一个"已校验完整版本"。
     * 快照未知时不与其他未知快照合并，也不推断与已知快照同版本。
     */
    private static SnapshotState snapshot(
            Map<String, DocumentState> documents, String documentId, String snapshotId) {
        DocumentState document = document(documents, documentId);
        String key = snapshotId == null || snapshotId.isBlank() ? UNKNOWN_SNAPSHOT : snapshotId;
        return document.snapshots.computeIfAbsent(key, ignored -> new SnapshotState(document.documentId, snapshotId));
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
        /** 按快照分开的覆盖状态（F4）；未知快照单独成键，不与其他版本合并。 */
        private final Map<String, SnapshotState> snapshots = new LinkedHashMap<>();

        private DocumentState(String documentId) {
            this.documentId = documentId;
        }
    }

    private static final class SnapshotState {
        private final String documentId;
        private final String snapshotId;
        private String processingStatus;
        private boolean outlineObtained;
        private String outlineStructure;
        private boolean outlineTruncated;
        private int outlineBlankSections;
        /** 该（文档, 快照）下提纲工具调用失败次数。 */
        private int outlineFailures;
        private final Set<String> outlineHeadings = new LinkedHashSet<>();
        private int readItems;
        /** 从标题起点读到的标题（可算已读该节）。 */
        private final Set<String> fullHeadings = new LinkedHashSet<>();
        /** 只读到后缀的标题（不能算已读该节，前缀未覆盖）。 */
        private final Set<String> partialHeadings = new LinkedHashSet<>();
        /** 已读取的 (chunkNo, fromOffset) 范围起点，用于判断文档前缀是否被跳过。 */
        private final List<int[]> readRanges = new ArrayList<>();
        private boolean readTruncated;
        private Integer pendingContinuationChunk;
        private int pendingContinuationOffset;
        private boolean pendingContinuationUnknown;

        private SnapshotState(String documentId, String snapshotId) {
            this.documentId = documentId;
            this.snapshotId = snapshotId;
        }

        private void observeProcessingStatus(String value) {
            if (processingStatus == null) processingStatus = value;
        }

        /**
         * 消费续读点（F3-2）：本次读取的首个条目正好从上次 continuation 声明的位置开始时，
         * 该续读点已被满足，续读链不再声明"后续内容未读完"。
         */
        private void consumeContinuation(int chunkNo, int fromOffset) {
            if (pendingContinuationChunk == null) return;
            if (pendingContinuationChunk == chunkNo && pendingContinuationOffset == fromOffset) {
                pendingContinuationChunk = null;
                pendingContinuationOffset = 0;
                pendingContinuationUnknown = false;
            }
        }

        private int minReadChunk() {
            return readRanges.stream().mapToInt(range -> range[0]).min().orElse(0);
        }

        /**
         * 正文读取是否跳过了文档起始范围。判据：最小已读 chunk 大于 0，或者某个读取
         * 从 chunk 0 的非 0 偏移开始（chunk 0 的前缀从未被读取）。
         */
        private boolean skippedDocumentPrefix() {
            if (readRanges.isEmpty()) return false;
            if (minReadChunk() > 0) return true;
            return readRanges.stream().anyMatch(range -> range[0] == 0 && range[1] > 0);
        }
    }
}
