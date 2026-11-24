package com.shitulelv.aicollab.planning.infrastructure.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.document.application.view.DocumentSearchHit;
import com.shitulelv.aicollab.planning.application.AttemptSourceRegistry;
import com.shitulelv.aicollab.planning.domain.PlanSource;
import com.shitulelv.aicollab.retrieval.application.ProjectKnowledgeRetrieval;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * {@code search_project_knowledge}：语义检索项目知识库文档，让任务拆解引用项目实际材料
 * （设计文档 §5.2）。检索与权限统一经 {@link ProjectKnowledgeRetrieval}——
 * planner 只能检索当前项目文档，非成员失败关闭。
 */
@Component
public class SearchProjectKnowledgePlanningTool implements PlanningReadOnlyTool {

    /** 单次返回摘录数上限（有界检索）。 */
    static final int MAX_TOP_K = 8;
    /** 单条摘录内容进入结果的字符上限（清洗前先裁剪，避免超长正文）。 */
    static final int EXCERPT_CHARS = 300;

    private final ProjectKnowledgeRetrieval retrieval;
    private final ObjectMapper json;

    public SearchProjectKnowledgePlanningTool(ProjectKnowledgeRetrieval retrieval, ObjectMapper json) {
        this.retrieval = retrieval;
        this.json = json;
    }

    @Override public String name() { return "search_project_knowledge"; }

    @Override public String description() {
        return "按语义检索当前项目已索引的文档，返回相关摘录（含文档名/标题），用于让任务拆解引用项目实际材料。"
                + "参数：query（必填，1-500 字），limit（可选，1-8，默认 5）。结果为空说明未命中，不代表项目没有资料。";
    }

    @Override
    public JsonNode execute(JsonNode arguments, UUID projectId, UUID userId) {
        return executeInternal(arguments, projectId, userId, null);
    }

    @Override
    public JsonNode execute(
            JsonNode arguments, UUID projectId, UUID userId, AttemptSourceRegistry sourceRegistry) {
        return executeInternal(arguments, projectId, userId, sourceRegistry);
    }

    private JsonNode executeInternal(
            JsonNode arguments, UUID projectId, UUID userId, AttemptSourceRegistry sourceRegistry) {
        String query = arguments != null && arguments.hasNonNull("query")
                ? arguments.get("query").asText() : null;
        if (query == null || query.isBlank()) {
            return json.createObjectNode().put("error", "QUERY_REQUIRED");
        }
        query = query.length() > 500 ? query.substring(0, 500) : query;
        int topK = arguments != null && arguments.hasNonNull("limit")
                ? Math.max(1, Math.min(MAX_TOP_K, arguments.get("limit").asInt(5))) : 5;
        List<DocumentSearchHit> hits;
        try {
            // 全项目检索（不限定文档）；成员权限由门面在任何检索动作前强制。
            // 非成员（PROJECT_NOT_FOUND）保持原样上抛，不得被降级为“检索不可用”；
            // embedding 失败等其余运行时异常仍收敛为错误结果而非中断生成。
            hits = retrieval.retrieve(new ProjectKnowledgeRetrieval.IntentRequest(
                    projectId, userId, query, List.of(), topK,
                    ProjectKnowledgeRetrieval.RetrievalIntent.PLANNING_TOOL_LOOKUP)).hits();
        } catch (BusinessException failure) {
            if (failure.getErrorCode() == ErrorCode.PROJECT_NOT_FOUND) throw failure;
            return json.createObjectNode()
                    .put("error", "SEARCH_UNAVAILABLE")
                    .put("message", "知识检索暂不可用，请基于已有上下文继续。");
        } catch (RuntimeException failure) {
            return json.createObjectNode()
                    .put("error", "SEARCH_UNAVAILABLE")
                    .put("message", "知识检索暂不可用，请基于已有上下文继续。");
        }
        ObjectNode data = json.createObjectNode();
        data.put("returned", hits.size());
        ArrayNode items = data.putArray("hits");
        int registeredCount = 0;
        for (DocumentSearchHit hit : hits) {
            ObjectNode item = items.addObject();
            Optional<PlanSource> registered = sourceRegistry != null
                    ? sourceRegistry.register(hit) : Optional.empty();
            if (registered.isPresent()) {
                item.put("sourceRef", registered.get().ref());
                registeredCount++;
            }
            if (hit.documentId() != null) item.put("documentId", hit.documentId().toString());
            if (hit.id() != null) item.put("chunkId", hit.id().toString());
            item.put("document", hit.originalFilename());
            if (hit.heading() != null) item.put("heading", hit.heading());
            String content = hit.content() == null ? "" : hit.content();
            if (content.length() > EXCERPT_CHARS) content = content.substring(0, EXCERPT_CHARS) + "… [truncated]";
            item.put("excerpt", content);
            item.put("similarity", hit.similarity());
        }
        data.put("registeredSourceCount", registeredCount);
        data.put("fieldGuide", sourceRegistry != null
                ? "hits 中带有 sourceRef（如 S5）的片段已动态注册进本轮权威来源表，可直接作为里程碑或任务的 sourceRefs 引用；未分配 sourceRef 的条目说明来源上限或预算已满。"
                : "hits 是相关片段（不是全文）；sourceRefs 只能引用规划输入 SOURCES 中列出的 ref，这里的摘录用于理解项目背景，不能直接当作 sourceRef。");
        return data;
    }
}
