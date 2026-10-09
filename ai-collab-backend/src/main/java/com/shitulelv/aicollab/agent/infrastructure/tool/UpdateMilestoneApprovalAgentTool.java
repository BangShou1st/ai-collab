package com.shitulelv.aicollab.agent.infrastructure.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.shitulelv.aicollab.agent.domain.tool.AgentToolContext;
import com.shitulelv.aicollab.agent.domain.tool.AgentToolDefinition;
import com.shitulelv.aicollab.agent.domain.tool.AgentToolResult;
import com.shitulelv.aicollab.agent.domain.model.AgentProposalFamily;
import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.work.api.dto.UpdateMilestoneRequest;
import com.shitulelv.aicollab.work.application.service.MilestoneApplicationService;
import com.shitulelv.aicollab.work.application.view.MilestoneView;
import jakarta.validation.Validator;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

@Component
public class UpdateMilestoneApprovalAgentTool extends AbstractApprovalWriteAgentTool {
    private final MilestoneApplicationService milestones;

    public UpdateMilestoneApprovalAgentTool(
            ObjectMapper json, Validator validator, MilestoneApplicationService milestones) {
        super(json, validator);
        this.milestones = milestones;
    }

    @Override public String name() { return "update_milestone_after_approval"; }

    @Override
    public AgentProposalFamily proposalFamily() {
        return AgentProposalFamily.MILESTONE_UPDATE;
    }

    /**
     * 参数契约按调用形态区分（同 update_task_after_approval）：
     * 新建提案必填 milestoneId 与 changes 内完整字段（含 status/sortOrder/version）；
     * 带 approvalId 的可信 PENDING 提案修订可只提交变化字段（x-approval-patch 跳过 required）。
     */
    @Override
    public AgentToolDefinition definition() {
        return AgentToolDefinition.fromJson(name(),
                "更新既有里程碑的提案；必须经过项目管理员批准后才写入。"
                        + "新建提案时提交 milestoneId、changes（含名称、状态、sortOrder 与该里程碑当前 version）；"
                        + "修订可信 PENDING 提案时携带 approvalId，只提交本轮变化字段。",
                """
                {"type":"object","additionalProperties":false,"x-approval-patch":true,
                 "required":["milestoneId","changes"],
                 "properties":{
                   "milestoneId":{"type":"string","format":"uuid","description":"目标里程碑 ID"},
                   "approvalId":{"type":"string","format":"uuid",
                     "description":"仅修订可信上下文中的 PENDING 提案时填写；新建时不要填写"},
                   "changes":{"type":"object","additionalProperties":false,
                     "description":"要更新的字段。新建提案必须提供全部业务字段（name、status、sortOrder、version，由执行边界 DTO 校验）；修订 PENDING 提案时可只提交变化字段",
                     "properties":{
                       "name":{"type":"string","minLength":1,"maxLength":100,"description":"里程碑名称"},
                       "description":{"type":["string","null"],"maxLength":1000,"description":"里程碑说明"},
                       "startDate":{"type":["string","null"],"format":"date"},
                       "endDate":{"type":["string","null"],"format":"date"},
                       "targetDate":{"type":["string","null"],"format":"date"},
                       "status":{"type":["string","null"],"enum":["PLANNED","ACTIVE","COMPLETED","CANCELED",null]},
                       "sortOrder":{"type":["integer","null"],"minimum":0},
                       "version":{"type":"integer","minimum":0,"description":"里程碑当前版本号，乐观锁；新建提案必填"}}}}}
                """,
                true);
    }

    @Override
    public JsonNode normalize(AgentToolContext context, JsonNode arguments) {
        UUID milestoneId = requiredId(arguments, "milestoneId");
        JsonNode payload = arguments.has("changes") ? arguments.get("changes") : arguments;
        ObjectNode normalized = json.createObjectNode();
        normalized.put("milestoneId", milestoneId.toString());
        normalized.set("changes", tree(request(payload, UpdateMilestoneRequest.class)));
        return normalized;
    }

    @Override
    public JsonNode diff(AgentToolContext context, JsonNode normalized) {
        UUID milestoneId = UUID.fromString(normalized.path("milestoneId").asText());
        Object before = milestones.list(context.projectId(), context.userId()).stream()
                .filter(item -> item.id().equals(milestoneId))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("里程碑不存在"));
        return tree(java.util.Map.of("operation", "UPDATE", "before", before, "after", normalized.get("changes")));
    }

    @Override
    public AgentToolResult execute(AgentToolContext context, JsonNode arguments) {
        UUID milestoneId = requiredId(arguments, "milestoneId");
        UpdateMilestoneRequest request = request(arguments.get("changes"), UpdateMilestoneRequest.class);

        return new AgentToolResult(tree(milestones.update(
                context.projectId(), milestoneId, request, context.userId())), List.of(), List.of());
    }

    /**
     * 执行前重新校验。
     * 检查：目标实体仍存在、属于当前项目、参数仍符合 Schema、业务条件仍成立。
     */
    @Override
    public void revalidate(AgentToolContext context, JsonNode arguments) {
        UUID milestoneId = requiredId(arguments, "milestoneId");

        // 1. 目标实体仍存在且属于当前项目
        MilestoneView milestone = milestones.list(context.projectId(), context.userId()).stream()
                .filter(item -> item.id().equals(milestoneId))
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.AGENT_APPROVAL_REVALIDATION_FAILED,
                        "里程碑不存在或不属于当前项目"));

        // 2. 参数仍符合 Schema（通过 request 方法校验）
        UpdateMilestoneRequest request = request(arguments.get("changes"), UpdateMilestoneRequest.class);

        if (request.version() == null || request.version() != milestone.version()) {
            throw new BusinessException(ErrorCode.AGENT_APPROVAL_VERSION_CONFLICT);
        }

        // 3. 业务条件仍成立（例如，里程碑不能已完成才能更新）
        // 注意：具体的业务条件校验在 MilestoneApplicationService.update 中会进行
    }

    private static UUID requiredId(JsonNode arguments, String name) {
        try {
            return UUID.fromString(arguments.path(name).asText());
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException(name + " 必须是 UUID", exception);
        }
    }
}
