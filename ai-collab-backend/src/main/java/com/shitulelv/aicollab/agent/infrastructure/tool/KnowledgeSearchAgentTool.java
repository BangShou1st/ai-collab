package com.shitulelv.aicollab.agent.infrastructure.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.shitulelv.aicollab.agent.domain.model.AgentCitation;
import com.shitulelv.aicollab.agent.domain.tool.AgentTool;
import com.shitulelv.aicollab.agent.domain.tool.AgentToolContext;
import com.shitulelv.aicollab.agent.domain.tool.AgentToolDefinition;
import com.shitulelv.aicollab.agent.domain.tool.AgentToolResult;
import com.shitulelv.aicollab.document.application.service.DocumentSearchService;
import com.shitulelv.aicollab.knowledge.domain.model.KnowledgeSource;
import com.shitulelv.aicollab.knowledge.domain.service.KnowledgeContextBuilder;
import com.shitulelv.aicollab.project.domain.policy.ProjectAccessGuard;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class KnowledgeSearchAgentTool implements AgentTool {
    private static final Logger log = LoggerFactory.getLogger(KnowledgeSearchAgentTool.class);
    /**
     * D-RAG-01B：Agent 跨文档来源选择的内部有界候选池，绝对上限 20。
     * 外部 {@code limit}（1–12）只决定最终返回来源数，不决定候选窗口大小；
     * 候选池放大到 20 才能让深排位的跨文档证据（Top8 之外、Top20 之内）有机会进入上下文。
     * 这是一次带 {@code LIMIT 20} 的有界检索，不是无界查询，也不产生 N+1。
     */
    private static final int MAX_CANDIDATE_POOL = 20;
    private static final Pattern EXPLICIT_DOCUMENT_NAME = Pattern.compile("《([^《》]{1,200})》");
    private final ProjectAccessGuard access;
    private final DocumentSearchService search;
    private final KnowledgeContextBuilder contextBuilder;
    private final ObjectMapper json;

    public KnowledgeSearchAgentTool(
            ProjectAccessGuard access,
            DocumentSearchService search,
            KnowledgeContextBuilder contextBuilder,
            ObjectMapper json) {
        this.access = access;
        this.search = search;
        this.contextBuilder = contextBuilder;
        this.json = json;
    }

    @Override public String name() { return "search_project_knowledge"; }
    @Override public boolean writesBusinessData() { return false; }

    @Override
    public AgentToolDefinition definition() {
        return AgentToolDefinition.fromJson(name(),
                "按语义检索当前项目已索引的文档，返回相关摘录、来源身份（documentId/chunkId/文件名/标题/页码）与相似度。"
                        + "若用户原始请求用《文档名》明确限定来源，工具会按该原始请求精确限定文档范围；模型不能扩大该范围。"
                        + "返回结果只是相关片段（coverage=RELEVANT_EXCERPTS_ONLY），不代表全文已读；"
                        + "需要上下文时用 get_document_outline / read_document_section 续读。"
                        + "结果为空说明检索未命中，不等于项目资料不存在。",
                """
                {"type":"object","additionalProperties":false,"required":["query"],
                 "properties":{
                   "query":{"type":"string","minLength":1,"maxLength":1000,"description":"检索问题或关键词"},
                   "documentIds":{"type":"array","maxItems":20,"uniqueItems":true,
                     "items":{"type":"string","format":"uuid"},
                     "description":"可选：只在指定文档内检索；不填则在全部已索引文档中检索"},
                   "limit":{"type":"integer","minimum":1,"maximum":12,"default":8,
                     "description":"返回的摘录条数上限，默认 8"}}}
                """,
                false);
    }

    @Override
    public AgentToolResult execute(AgentToolContext context, JsonNode arguments) {
        AgentToolArguments.requireFields(arguments, Set.of("query", "documentIds", "limit"));
        String query = AgentToolArguments.text(arguments, "query", 1000, true);
        List<UUID> requestedDocumentIds = AgentToolArguments.uuidList(arguments, "documentIds", 20);
        boolean modelSpecifiedScope = arguments.has("documentIds") && !arguments.get("documentIds").isNull();
        int limit = AgentToolArguments.integer(arguments, "limit", 8, 1, 12);
        access.requireMember(context.projectId(), context.userId());
        List<String> explicitNames = explicitDocumentNames(context.userGoal());
        boolean userSpecifiedScope = !explicitNames.isEmpty();
        List<UUID> effectiveDocumentIds = requestedDocumentIds;
        boolean unresolvedUserScope = false;
        if (userSpecifiedScope) {
            List<UUID> resolvedUserScope = search.resolveNamedDocumentIds(context.projectId(), explicitNames);
            if (resolvedUserScope.isEmpty()) {
                unresolvedUserScope = true;
                effectiveDocumentIds = List.of();
            } else if (modelSpecifiedScope) {
                effectiveDocumentIds = resolvedUserScope.stream()
                        .filter(requestedDocumentIds::contains)
                        .toList();
            } else {
                effectiveDocumentIds = resolvedUserScope;
            }
        }
        // D-RAG-01B：候选池固定为有界 20（绝对上限），limit（1–12）只决定最终来源数；
        // 选择走跨文档入口：剔除不合格候选后跨文档优先、文档感知去重、并公平分配 8000 字符正文预算。
        // 知识问答仍走 KnowledgeContextBuilder 的默认上限（5）与默认入口，行为逐字不变。
        List<com.shitulelv.aicollab.document.application.view.DocumentSearchHit> hits =
                unresolvedUserScope || (userSpecifiedScope && effectiveDocumentIds.isEmpty())
                        ? List.of()
                        : search.search(context.projectId(), query,
                                userSpecifiedScope ? effectiveDocumentIds : requestedDocumentIds,
                                MAX_CANDIDATE_POOL);
        if (userSpecifiedScope) {
            List<UUID> enforcedScope = effectiveDocumentIds;
            hits = hits.stream().filter(hit -> enforcedScope.contains(hit.documentId())).toList();
        }
        var knowledge = contextBuilder.buildDiverse(hits, limit);
        ArrayNode sources = json.createArrayNode();
        List<AgentCitation> citations = knowledge.sources().stream().map(source -> {
            ObjectNode item = sources.addObject();
            item.put("documentId", source.documentId().toString());
            item.put("chunkId", source.chunkId().toString());
            item.put("filename", source.originalFilename());
            item.put("heading", source.heading());
            item.put("pageNumber", pageNumber(source.metadata()));
            item.put("quote", TaskListAgentTool.truncate(source.content(), 600));
            item.put("similarity", source.similarity());
            item.set("sourceIdentity",json.valueToTree(source.metadata()));
            return citation(source);
        }).toList();
        ObjectNode data = json.createObjectNode();
        data.set("sources", sources);
        data.put("coverage","RELEVANT_EXCERPTS_ONLY");
        data.put("fullDocumentRead",false);
        data.put("userScopeEnforced", userSpecifiedScope);
        if (userSpecifiedScope) data.set("effectiveDocumentIds", json.valueToTree(effectiveDocumentIds));
        return new AgentToolResult(data, citations, List.of());
    }

    static List<String> explicitDocumentNames(String userGoal) {
        if (userGoal == null || userGoal.isBlank()) return List.of();
        Matcher matcher = EXPLICIT_DOCUMENT_NAME.matcher(userGoal);
        List<String> names = new ArrayList<>();
        while (matcher.find()) {
            String name = matcher.group(1).strip();
            if (!name.isEmpty() && !names.contains(name)) names.add(name);
        }
        return List.copyOf(names);
    }

    private static AgentCitation citation(KnowledgeSource source) {
        return new AgentCitation(
                source.documentId(), source.chunkId(), source.originalFilename(),
                source.heading(), pageNumber(source.metadata()),
                TaskListAgentTool.truncate(source.content(), 600), source.similarity());
    }

    private static Integer pageNumber(Map<String, Object> metadata) {
        if (metadata == null) return null;
        Object value = metadata.get("pageNumber");
        if (value instanceof Number number) return number.intValue();
        try {
            return value == null ? null : Integer.valueOf(value.toString());
        } catch (NumberFormatException malformed) {
            log.debug("Unparseable pageNumber in document chunk metadata: {}", value);
            return null;
        }
    }
}
