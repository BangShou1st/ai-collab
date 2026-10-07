package com.shitulelv.aicollab.agent.infrastructure.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.agent.domain.model.AgentProposalFamily;
import com.shitulelv.aicollab.agent.domain.tool.AgentToolContext;
import com.shitulelv.aicollab.agent.domain.tool.AgentToolDefinition;
import com.shitulelv.aicollab.agent.domain.tool.AgentToolResult;
import com.shitulelv.aicollab.project.application.service.ProjectApplicationService;
import com.shitulelv.aicollab.work.api.dto.CreateMilestoneRequest;
import com.shitulelv.aicollab.work.application.service.MilestoneApplicationService;
import jakarta.validation.Validator;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class CreateMilestoneApprovalAgentTool extends AbstractApprovalWriteAgentTool {
    private final MilestoneApplicationService milestones;
    private final ProjectApplicationService projects;

    public CreateMilestoneApprovalAgentTool(
            ObjectMapper json, Validator validator, MilestoneApplicationService milestones,
            ProjectApplicationService projects) {
        super(json, validator);
        this.milestones = milestones;
        this.projects = projects;
    }

    @Override public String name() { return "create_milestone_after_approval"; }

    @Override
    public AgentProposalFamily proposalFamily() {
        return AgentProposalFamily.MILESTONE_CREATE;
    }

    @Override
    public AgentToolDefinition definition() {
        return AgentToolDefinition.fromJson(name(),
                "创建里程碑提案；必须经过项目管理员批准后才写入。名称必填；"
                        + "日期可选（开始/结束/目标日期，格式 YYYY-MM-DD），状态默认 PLANNED。",
                """
                {"type":"object","additionalProperties":false,"required":["name"],
                 "properties":{
                   "name":{"type":"string","minLength":1,"maxLength":100,"description":"里程碑名称"},
                   "description":{"type":["string","null"],"maxLength":1000,"description":"里程碑说明"},
                   "startDate":{"type":["string","null"],"format":"date","description":"开始日期 YYYY-MM-DD"},
                   "endDate":{"type":["string","null"],"format":"date","description":"结束日期 YYYY-MM-DD"},
                   "targetDate":{"type":["string","null"],"format":"date","description":"目标日期 YYYY-MM-DD"},
                   "status":{"type":["string","null"],"enum":["PLANNED","ACTIVE","COMPLETED","CANCELED",null],
                     "description":"里程碑状态，默认 PLANNED"},
                   "sortOrder":{"type":["integer","null"],"minimum":0,"description":"展示顺序"}}}
                """,
                true);
    }

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

    /**
     * 执行前重新校验。
     * 检查：项目仍存在、参数仍符合 Schema、业务前置条件成立。
     */
    @Override
    public void revalidate(AgentToolContext context, JsonNode arguments) {
        // 1. 项目仍存在（通过 get 方法校验，不存在会抛出异常）
        projects.get(context.projectId(), context.userId());

        // 2. 参数仍符合 Schema（通过 request 方法校验）
        CreateMilestoneRequest request = request(arguments, CreateMilestoneRequest.class);

        // 3. 业务前置条件成立（名称不能为空，长度限制等已通过 validation 校验）
    }
}
