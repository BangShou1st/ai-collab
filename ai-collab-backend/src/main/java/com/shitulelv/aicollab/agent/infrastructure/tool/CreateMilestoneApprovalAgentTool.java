package com.shitulelv.aicollab.agent.infrastructure.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.agent.domain.tool.AgentToolContext;
import com.shitulelv.aicollab.agent.domain.tool.AgentToolResult;
import com.shitulelv.aicollab.work.api.dto.CreateMilestoneRequest;
import com.shitulelv.aicollab.work.application.service.MilestoneApplicationService;
import jakarta.validation.Validator;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class CreateMilestoneApprovalAgentTool extends AbstractApprovalWriteAgentTool {
    private final MilestoneApplicationService milestones;

    public CreateMilestoneApprovalAgentTool(
            ObjectMapper json, Validator validator, MilestoneApplicationService milestones) {
        super(json, validator);
        this.milestones = milestones;
    }

    @Override public String name() { return "create_milestone_after_approval"; }

    @Override
    public JsonNode normalize(AgentToolContext context, JsonNode arguments) {
        return tree(request(arguments, CreateMilestoneRequest.class));
    }

    @Override
    public JsonNode diff(AgentToolContext context, JsonNode normalizedArguments) {
        return tree(java.util.Map.of("operation", "CREATE", "after", normalizedArguments));
    }

    @Override
    public AgentToolResult execute(AgentToolContext context, JsonNode arguments) {
        return new AgentToolResult(tree(milestones.create(
                context.projectId(), request(arguments, CreateMilestoneRequest.class), context.userId())),
                List.of(), List.of());
    }
}
