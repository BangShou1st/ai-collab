package com.shitulelv.aicollab.agent.application.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.agent.application.view.AgentRunView;
import com.shitulelv.aicollab.agent.domain.model.*;
import com.shitulelv.aicollab.agent.infrastructure.repository.AgentRepository;
import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.document.application.service.DocumentApplicationService;
import com.shitulelv.aicollab.project.domain.model.ProjectRole;
import com.shitulelv.aicollab.project.domain.policy.ProjectAccessGuard;
import com.shitulelv.aicollab.work.application.service.MilestoneApplicationService;
import com.shitulelv.aicollab.work.application.service.TaskApplicationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import com.shitulelv.aicollab.planning.application.TaskPlanQueryService;

import java.util.Map;
import java.util.UUID;

/**
 * 组装可信运行上下文。
 * - projectId 来自受信任的请求路径或 Run
 * - requesterId 来自认证用户
 * - role 来自项目成员关系
 * - 模型参数中的 projectId、userId、role 必须忽略或拒绝
 * <p>
 * 页面上下文校验规则：
 * - 用户未提交实体 ID 时允许 null
 * - 用户明确提交实体 ID 后，必须执行正式 Application Service 校验
 * - 实体不存在：抛出 AGENT_CONTEXT_RESOURCE_INVALID
 * - 实体属于其他项目：抛出 AGENT_CONTEXT_RESOURCE_INVALID
 * - 用户无权访问：返回 FORBIDDEN 类错误
 * - 不捕获所有 Exception
 * - 不记录其他项目实体的名称或内容
 */
@Service
public class AgentContextAssembler {
    private static final Logger log = LoggerFactory.getLogger(AgentContextAssembler.class);

    private final ProjectAccessGuard access;
    private final AgentRepository repository;
    private final TaskApplicationService tasks;
    private final DocumentApplicationService documents;
    private final MilestoneApplicationService milestones;
    private final ObjectMapper json;
    private final TaskPlanQueryService plans;

    @Autowired
    public AgentContextAssembler(
            ProjectAccessGuard access,
            AgentRepository repository,
            TaskApplicationService tasks,
            DocumentApplicationService documents,
            MilestoneApplicationService milestones,
            ObjectMapper json,
            TaskPlanQueryService plans) {
        this.access = access;
        this.repository = repository;
        this.tasks = tasks;
        this.documents = documents;
        this.milestones = milestones;
        this.json = json;
        this.plans = plans;
    }

    public AgentContextAssembler(
            ProjectAccessGuard access,
            AgentRepository repository,
            TaskApplicationService tasks,
            DocumentApplicationService documents,
            MilestoneApplicationService milestones,
            ObjectMapper json) {
        this(access, repository, tasks, documents, milestones, json, null);
    }

    /**
     * 从 Run 和请求参数组装可信执行上下文。
     * 模型参数中的 projectId、userId、role 必须忽略。
     */
    public AgentExecutionContext assemble(
            AgentRunView run,
            String skillCode,
            AgentPageContext pageContext) {
        // 1. 验证用户是项目成员
        ProjectRole projectRole = access.requireMember(run.projectId(), run.requesterId());

        // 2. 获取用户在项目中的角色
        String role = projectRole != null ? projectRole.name() : "MEMBER";

        // 3. 验证页面上下文中的实体属于当前项目
        AgentPageContext validatedPage = validatePageContext(run.projectId(), run.requesterId(), pageContext);

        // 4. 获取 Skill 对应的预算限制
        AgentRuntimeLimits limits = AgentRuntimeLimits.defaults();

        // 5. 构建可信上下文
        return new AgentExecutionContext(
                run.id(),
                run.sessionId(),
                run.projectId(),
                run.requesterId(),
                role,
                run.scheduled(),
                validatedPage,
                limits,
                run.depth());
    }

    /**
     * 验证页面上下文。
     * 用户未提交实体 ID 时允许 null。
     * 用户明确提交实体 ID 后，必须验证实体属于当前项目。
     * 实体不存在或跨项目时抛出明确异常，不得静默转换为 null。
     */
    private AgentPageContext validatePageContext(UUID projectId, UUID requesterId, AgentPageContext page) {
        if (page == null) {
            return AgentPageContext.empty();
        }

        UUID validatedTaskId = validateTask(projectId, requesterId, page.selectedTaskId());
        UUID validatedMilestoneId = validateMilestone(projectId, requesterId, page.selectedMilestoneId());
        UUID validatedDocumentId = validateDocument(projectId, requesterId, page.selectedDocumentId());
        UUID validatedPlanId = validatePlan(projectId, requesterId, page.selectedPlanId());

        return new AgentPageContext(
                page.route(),
                validatedTaskId,
                validatedMilestoneId,
                validatedDocumentId,
                validatedPlanId,
                page.filters());
    }

    /**
     * 验证任务。用户明确提交 taskId 时必须验证存在性和项目归属。
     * 实体不存在或跨项目时抛出 AGENT_CONTEXT_RESOURCE_INVALID。
     * 不得静默转换为 null。
     */
    private UUID validateTask(UUID projectId, UUID requesterId, UUID taskId) {
        if (taskId == null) return null;
        try {
            tasks.get(projectId, taskId, requesterId);
            return taskId;
        } catch (BusinessException e) {
            if (e.getErrorCode() == ErrorCode.TASK_NOT_FOUND) {
                // 用户明确提交了 taskId 但实体不存在 -> 拒绝
                throw new BusinessException(ErrorCode.AGENT_CONTEXT_RESOURCE_INVALID,
                        "页面上下文中的任务不存在或不属于当前项目");
            }
            // 其他业务异常（如权限不足）原样传播
            throw e;
        }
    }

    /**
     * 验证文档。用户明确提交 documentId 时必须验证存在性和项目归属。
     */
    private UUID validateDocument(UUID projectId, UUID requesterId, UUID documentId) {
        if (documentId == null) return null;
        try {
            documents.get(projectId, documentId, requesterId);
            return documentId;
        } catch (BusinessException e) {
            if (e.getErrorCode() == ErrorCode.DOCUMENT_NOT_FOUND) {
                throw new BusinessException(ErrorCode.AGENT_CONTEXT_RESOURCE_INVALID,
                        "页面上下文中的文档不存在或不属于当前项目");
            }
            throw e;
        }
    }

    /**
     * 验证里程碑。用户明确提交 milestoneId 时必须验证存在性和项目归属。
     */
    private UUID validateMilestone(UUID projectId, UUID requesterId, UUID milestoneId) {
        if (milestoneId == null) return null;
        try {
            milestones.get(projectId, milestoneId, requesterId);
            return milestoneId;
        } catch (BusinessException e) {
            if (e.getErrorCode() == ErrorCode.MILESTONE_NOT_FOUND) {
                throw new BusinessException(ErrorCode.AGENT_CONTEXT_RESOURCE_INVALID,
                        "页面上下文中的里程碑不存在或不属于当前项目");
            }
            throw e;
        }
    }

    private UUID validatePlan(UUID projectId, UUID requesterId, UUID planId) {
        if (planId == null) return null;
        try {
            if (plans == null) throw new BusinessException(ErrorCode.TASK_PLAN_NOT_FOUND);
            plans.detail(projectId, planId, requesterId);
            return planId;
        } catch (BusinessException failure) {
            if (failure.getErrorCode() == ErrorCode.TASK_PLAN_NOT_FOUND) {
                throw new BusinessException(ErrorCode.AGENT_CONTEXT_RESOURCE_INVALID,
                        "页面上下文中的规划不存在或不属于当前项目");
            }
            throw failure;
        }
    }
}
