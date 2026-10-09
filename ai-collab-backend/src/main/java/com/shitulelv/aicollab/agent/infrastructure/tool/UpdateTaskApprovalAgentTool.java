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
import com.shitulelv.aicollab.project.infrastructure.repository.ProjectMemberRepository;
import com.shitulelv.aicollab.work.api.dto.UpdateTaskRequest;
import com.shitulelv.aicollab.work.application.service.TaskApplicationService;
import com.shitulelv.aicollab.work.application.view.TaskView;
import jakarta.validation.Validator;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

@Component
public class UpdateTaskApprovalAgentTool extends AbstractApprovalWriteAgentTool {
    private final TaskApplicationService tasks;
    private final ProjectMemberRepository members;

    public UpdateTaskApprovalAgentTool(ObjectMapper json, Validator validator,
                                       TaskApplicationService tasks, ProjectMemberRepository members) {
        super(json, validator);
        this.tasks = tasks;
        this.members = members;
    }

    @Override public String name() { return "update_task_after_approval"; }

    @Override
    public AgentProposalFamily proposalFamily() {
        return AgentProposalFamily.TASK_UPDATE;
    }

    /**
     * 参数契约按调用形态区分：
     * <ul>
     *   <li>新建提案（无 approvalId）：taskId、changes.version 必填，changes 内为完整更新参数。</li>
     *   <li>可信 PENDING 提案修订（带 approvalId）：Registry 标记 x-approval-patch，
     *       Validator 跳过 required——模型可以只提交本轮变化字段，
     *       未提及字段由 {@link com.shitulelv.aicollab.agent.domain.policy.AgentProposalArgumentMerger}
     *       在合并后保留，合并结果经 normalize 完整校验。</li>
     * </ul>
     * 注意：修订补丁中的 changes.version 非必填（旧提案版本可整体保留）；
     * 嵌套对象无法用 x-approval-patch 跳过 required，因此 changes 内不声明 required——
     * 新建时 version 必填由 DTO 校验（UpdateTaskRequest 的 @NotNull version）在执行边界保证。
     */
    @Override
    public AgentToolDefinition definition() {
        return AgentToolDefinition.fromJson(name(),
                "更新既有任务的提案；必须经过项目管理员批准后才写入。"
                        + "新建提案时提交 taskId、changes（含该任务当前 version）；"
                        + "修订可信 PENDING 提案时携带 approvalId，只提交本轮变化字段，未提及字段保持提案原值。",
                """
                {"type":"object","additionalProperties":false,"x-approval-patch":true,
                 "required":["taskId","changes"],
                 "properties":{
                   "taskId":{"type":"string","format":"uuid","description":"目标任务 ID"},
                   "approvalId":{"type":"string","format":"uuid",
                     "description":"仅修订可信上下文中的 PENDING 提案时填写；新建时不要填写"},
                   "changes":{"type":"object","additionalProperties":false,
                     "description":"要更新的字段；新建提案必须包含 version（该任务当前版本号）。修订 PENDING 提案时可省略未变化字段",
                     "properties":{
                       "title":{"type":"string","minLength":1,"maxLength":160},
                       "description":{"type":["string","null"],"maxLength":4000},
                       "milestoneId":{"type":["string","null"],"format":"uuid"},
                       "assigneeId":{"type":["string","null"],"format":"uuid"},
                       "status":{"type":["string","null"],"enum":["TODO","IN_PROGRESS","BLOCKED","DONE","CANCELED",null]},
                       "priority":{"type":["string","null"],"enum":["LOW","MEDIUM","HIGH","URGENT",null]},
                       "estimateHours":{"type":["number","null"],"minimum":0.5,"maximum":80},
                       "startDate":{"type":["string","null"],"format":"date"},
                       "dueDate":{"type":["string","null"],"format":"date"},
                       "version":{"type":"integer","minimum":0,"description":"任务当前版本号，乐观锁；新建提案必填"}}}}}
                """,
                true);
    }

    @Override
    public JsonNode normalize(AgentToolContext context, JsonNode arguments) {
        UUID taskId = requiredId(arguments, "taskId");
        JsonNode payload = arguments.has("changes") ? arguments.get("changes") : arguments;
        UpdateTaskRequest req = taskRequest(payload, UpdateTaskRequest.class);
        ObjectNode changes = (ObjectNode) tree(req);
        // 附加负责人显示名称，便于前端审批界面展示
        if (req.assigneeId() != null) {
            members.find(context.projectId(), req.assigneeId())
                    .ifPresent(m -> changes.put("assigneeName", m.displayName()));
        }
        ObjectNode normalized = json.createObjectNode();
        normalized.put("taskId", taskId.toString());
        normalized.set("changes", changes);
        return normalized;
    }

    @Override
    public JsonNode diff(AgentToolContext context, JsonNode normalized) {
        UUID taskId = UUID.fromString(normalized.path("taskId").asText());
        return tree(java.util.Map.of(
                "operation", "UPDATE",
                "before", tasks.get(context.projectId(), taskId, context.userId()),
                "after", normalized.get("changes")));
    }

    @Override
    public AgentToolResult execute(AgentToolContext context, JsonNode arguments) {
        UUID taskId = requiredId(arguments, "taskId");
        UpdateTaskRequest request = taskRequest(arguments.get("changes"), UpdateTaskRequest.class);
        return new AgentToolResult(tree(tasks.update(
                context.projectId(), taskId, request, context.userId())), List.of(), List.of());
    }

    /**
     * 执行前重新校验。
     * 检查：目标实体仍存在、属于当前项目、当前版本等于审批提案版本、
     * approver 仍有权限、更新字段仍合法、业务条件仍成立。
     */
    @Override
    public void revalidate(AgentToolContext context, JsonNode arguments) {
        UUID taskId = requiredId(arguments, "taskId");

        // 1. 目标实体仍存在且属于当前项目
        TaskView task = tasks.get(context.projectId(), taskId, context.userId());

        // 2. 参数仍符合 Schema（通过 request 方法校验）
        UpdateTaskRequest request = taskRequest(arguments.get("changes"), UpdateTaskRequest.class);

        if (request.version() == null || request.version() != task.version()) {
            throw new BusinessException(ErrorCode.AGENT_APPROVAL_VERSION_CONFLICT);
        }

        // 3. 业务条件仍成立（例如，任务不能已完成才能更新）
        // 注意：具体的业务条件校验在 TaskApplicationService.update 中会进行
    }

    private static UUID requiredId(JsonNode arguments, String name) {
        try {
            return UUID.fromString(arguments.path(name).asText());
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException(name + " 必须是 UUID", exception);
        }
    }
}
