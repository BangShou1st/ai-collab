package com.shitulelv.aicollab.agent.infrastructure.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.shitulelv.aicollab.agent.domain.model.AgentCitation;
import com.shitulelv.aicollab.agent.domain.tool.AgentTool;
import com.shitulelv.aicollab.agent.domain.tool.AgentToolContext;
import com.shitulelv.aicollab.agent.domain.tool.AgentToolResult;
import com.shitulelv.aicollab.document.application.service.DocumentSearchService;
import com.shitulelv.aicollab.knowledge.domain.model.KnowledgeSource;
import com.shitulelv.aicollab.knowledge.domain.service.KnowledgeContextBuilder;
import com.shitulelv.aicollab.project.domain.policy.ProjectAccessGuard;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class KnowledgeSearchAgentTool implements AgentTool {
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
            return citation(source);
        }).toList();
        ObjectNode data = json.createObjectNode();
        data.set("sources", sources);
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
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
