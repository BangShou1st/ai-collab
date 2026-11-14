package com.shitulelv.aicollab.agent.domain.model.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.shitulelv.aicollab.agent.domain.model.AgentScenario;

import java.util.Set;

/**
 * PROJECT_RESEARCH Scenario：项目研究。
 */
public final class ProjectResearchScenario implements AgentScenario {

    @Override
    public String code() { return "PROJECT_RESEARCH"; }

    @Override
    public String displayName() { return "项目研究"; }

    @Override
    public String description() { return "搜索知识库和文档，回答研究问题"; }

    @Override
    public Set<String> entryRoutes() { return Set.of("DOCUMENT_LIST", "DOCUMENT_DETAIL", "AGENT"); }

    @Override
    public Set<String> grantedTools() {
        return Set.of("list_project_documents", "get_document_outline", "read_document_section", "search_project_knowledge", "get_project_overview",
                "check_project_progress", "answer_project_question_with_sources",
                "list_project_memories");
    }

    @Override
    public JsonNode inputSchema() {
        ObjectNode schema = new ObjectMapper().createObjectNode();
        schema.put("type", "object");
        schema.put("additionalProperties", false);
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("query")
                .put("type", "string")
                .put("minLength", 1)
                .put("maxLength", 500)
                .put("description", "研究问题");
        return schema;
    }

    @Override
    public boolean allowWriteTools() { return false; }

    @Override
    public boolean allowExternalTools() { return true; }
}
