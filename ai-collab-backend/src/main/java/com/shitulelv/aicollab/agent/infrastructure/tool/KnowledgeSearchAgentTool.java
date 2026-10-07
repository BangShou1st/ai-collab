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
import java.util.Map;
import java.util.Set;

@Component
public class KnowledgeSearchAgentTool implements AgentTool {
    private static final Logger log = LoggerFactory.getLogger(KnowledgeSearchAgentTool.class);
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
        var documentIds = AgentToolArguments.uuidList(arguments, "documentIds", 20);
        int limit = AgentToolArguments.integer(arguments, "limit", 8, 1, 12);
        access.requireMember(context.projectId(), context.userId());
        var knowledge = contextBuilder.build(
                search.search(context.projectId(), query, documentIds, limit));
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
        return new AgentToolResult(data, citations, List.of());
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
