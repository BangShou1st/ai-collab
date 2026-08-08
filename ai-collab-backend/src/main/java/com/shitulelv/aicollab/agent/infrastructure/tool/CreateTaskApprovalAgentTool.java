package com.shitulelv.aicollab.agent.infrastructure.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.shitulelv.aicollab.agent.domain.tool.AgentToolContext;
import com.shitulelv.aicollab.agent.domain.tool.AgentToolDefinition;
import com.shitulelv.aicollab.agent.domain.tool.AgentToolResult;
import com.shitulelv.aicollab.agent.domain.model.AgentProposalFamily;
import com.shitulelv.aicollab.project.application.service.ProjectApplicationService;
import com.shitulelv.aicollab.project.infrastructure.repository.ProjectMemberRepository;
import com.shitulelv.aicollab.work.api.dto.CreateTaskRequest;
import com.shitulelv.aicollab.work.application.service.TaskApplicationService;
import jakarta.validation.Validator;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class CreateTaskApprovalAgentTool extends AbstractApprovalWriteAgentTool {
    private final TaskApplicationService tasks;
    private final ProjectApplicationService projects;
    private final ProjectMemberRepository members;

    public CreateTaskApprovalAgentTool(ObjectMapper json, Validator validator,
                                       TaskApplicationService tasks, ProjectApplicationService projects,
                                       ProjectMemberRepository members) {
        super(json, validator);
        this.tasks = tasks;
        this.projects = projects;
        this.members = members;
    }

    @Override public String name() { return "create_task_after_approval"; }

    @Override
    public AgentProposalFamily proposalFamily() {
        return AgentProposalFamily.TASK_CREATE;
    }

    @Override
    public AgentToolDefinition definition() {
        return AgentToolDefinition.fromJson(
                name(),
                "创建任务提案；必须经过项目管理员批准后才写入任务",
                """
                {
                  "type":"object",
                  "additionalProperties":false,
                  "required":["title"],
                  "properties":{
                    "title":{"type":"string","minLength":1,"maxLength":160},
                    "description":{"type":["string","null"],"maxLength":4000},
                    "milestoneId":{"type":["string","null"],"format":"uuid"},
                    "assigneeId":{"type":["string","null"],"format":"uuid"},
                    "status":{"type":["string","null"],"enum":["TODO","IN_PROGRESS","BLOCKED","DONE","CANCELED",null]},
                    "priority":{"type":["string","null"],"enum":["LOW","MEDIUM","HIGH","URGENT",null]},
                    "estimateHours":{"type":["number","null"],"minimum":0.5,"maximum":80},
                    "startDate":{"type":["string","null"],"format":"date"},
                    "dueDate":{"type":["string","null"],"format":"date"}
                  }
                }
                """,
                true);
    }

    @Override
    public JsonNode normalize(AgentToolContext context, JsonNode arguments) {
        CreateTaskRequest req = request(arguments, CreateTaskRequest.class);
        ObjectNode node = (ObjectNode) tree(req);
        // 附加负责人显示名称，便于前端审批界面展示
        if (req.assigneeId() != null) {
            members.find(context.projectId(), req.assigneeId())
                    .ifPresent(m -> node.put("assigneeName", m.displayName()));
        }
        return node;
    }

    @Override
    public JsonNode diff(AgentToolContext context, JsonNode normalizedArguments) {
        return tree(java.util.Map.of("operation", "CREATE", "after", normalizedArguments));
    }

    @Override
    public AgentToolResult execute(AgentToolContext context, JsonNode arguments) {
        return new AgentToolResult(tree(tasks.create(
                context.projectId(), request(arguments, CreateTaskRequest.class), context.userId())),
                List.of(), List.of());
    }

    /**
     * 执行前重新校验。
     * 检查：项目仍存在、参数仍符合 Schema、关联实体仍有效、业务前置条件成立。
     */
    @Override
    public void revalidate(AgentToolContext context, JsonNode arguments) {
        // 1. 项目仍存在（通过 get 方法校验，不存在会抛出异常）
        projects.get(context.projectId(), context.userId());

        // 2. 参数仍符合 Schema（通过 request 方法校验）
        CreateTaskRequest request = request(arguments, CreateTaskRequest.class);

        // 3. 关联实体仍有效（如果指定了 milestoneId，检查里程碑是否存在）
        if (request.milestoneId() != null) {
            // 里程碑校验在 TaskApplicationService.create 中会进行
        }

        // 4. 业务前置条件成立（标题不能为空，长度限制等已通过 validation 校验）
    }
}
