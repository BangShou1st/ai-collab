package com.shitulelv.aicollab.agent.infrastructure.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.shitulelv.aicollab.agent.domain.tool.AgentTool;
import com.shitulelv.aicollab.agent.domain.tool.AgentToolContext;
import com.shitulelv.aicollab.agent.domain.tool.AgentToolResult;
import org.springframework.stereotype.Component;

@Component
public class ProjectQuestionAgentTool implements AgentTool {
    private final KnowledgeSearchAgentTool search;

    public ProjectQuestionAgentTool(KnowledgeSearchAgentTool search) {
        this.search = search;
    }

    @Override public String name() { return "answer_project_question_with_sources"; }
    @Override public boolean writesBusinessData() { return false; }

    @Override
    public AgentToolResult execute(AgentToolContext context, JsonNode arguments) {
        return search.execute(context, arguments);
    }
}
