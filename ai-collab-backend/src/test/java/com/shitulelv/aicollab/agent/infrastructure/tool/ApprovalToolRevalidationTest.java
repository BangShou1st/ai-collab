package com.shitulelv.aicollab.agent.infrastructure.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.shitulelv.aicollab.agent.application.AgentApprovalService;
import com.shitulelv.aicollab.agent.application.view.AgentApprovalView;
import com.shitulelv.aicollab.agent.domain.tool.AgentToolContext;
import com.shitulelv.aicollab.agent.infrastructure.repository.AgentApprovalRepository;
import com.shitulelv.aicollab.agent.infrastructure.repository.AgentRepository;
import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.project.application.service.ProjectApplicationService;
import com.shitulelv.aicollab.work.application.service.MilestoneApplicationService;
import com.shitulelv.aicollab.work.application.service.TaskApplicationService;
import com.shitulelv.aicollab.work.application.view.TaskView;
import com.shitulelv.aicollab.work.application.view.MilestoneView;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 审批工具重校验测试。
 * 验证四个审批写工具的 revalidate 方法：
 * - 版本匹配检查
 * - 权限检查
 * - 实体存在检查
 * - 幂等性检查
 */
@ExtendWith(MockitoExtension.class)
class ApprovalToolRevalidationTest {
    private final ObjectMapper json = new ObjectMapper();

    @Mock
    private TaskApplicationService tasks;
    @Mock
    private MilestoneApplicationService milestones;
    @Mock
    private ProjectApplicationService projects;
    @Mock
    private Validator validator;
    @Mock
    private AgentApprovalService approvalService;
    @Mock
    private AgentApprovalRepository approvals;
    @Mock
    private AgentRepository repository;

    private CreateTaskApprovalAgentTool createTaskTool;
    private UpdateTaskApprovalAgentTool updateTaskTool;
    private CreateMilestoneApprovalAgentTool createMilestoneTool;
    private UpdateMilestoneApprovalAgentTool updateMilestoneTool;

    @BeforeEach
    void setUp() {
        createTaskTool = new CreateTaskApprovalAgentTool(json, validator, tasks, projects);
        updateTaskTool = new UpdateTaskApprovalAgentTool(json, validator, tasks);
        createMilestoneTool = new CreateMilestoneApprovalAgentTool(json, validator, milestones, projects);
        updateMilestoneTool = new UpdateMilestoneApprovalAgentTool(json, validator, milestones);
    }

    // ========== create_task_after_approval ==========

    @Test
    void createTaskValidationFailsWhenProjectDeleted() {
        AgentToolContext ctx = new AgentToolContext(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "OWNER", false, 0);
        JsonNode arguments = json.createObjectNode().put("title", "Test Task");

        when(projects.get(any(), any())).thenThrow(new BusinessException(ErrorCode.PROJECT_NOT_FOUND));

        assertThatThrownBy(() -> createTaskTool.revalidate(ctx, arguments))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.PROJECT_NOT_FOUND);
    }

    @Test
    void createTaskValidationSucceedsWithValidArguments() {
        AgentToolContext ctx = new AgentToolContext(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "OWNER", false, 0);
        JsonNode arguments = json.createObjectNode().put("title", "Test Task");

        // 不抛异常即为成功
        createTaskTool.revalidate(ctx, arguments);
    }

    // ========== update_task_after_approval ==========

    @Test
    void updateTaskValidationFailsWhenTaskDeleted() {
        UUID projectId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        AgentToolContext ctx = new AgentToolContext(
                UUID.randomUUID(), projectId, UUID.randomUUID(),
                "OWNER", false, 0);

        JsonNode arguments = json.createObjectNode()
                .put("taskId", taskId.toString());
        ObjectNode changes = json.createObjectNode().put("title", "Updated Task");
        ((ObjectNode) arguments).set("changes", changes);

        when(tasks.get(eq(projectId), eq(taskId), any()))
                .thenThrow(new BusinessException(ErrorCode.TASK_NOT_FOUND));

        assertThatThrownBy(() -> updateTaskTool.revalidate(ctx, arguments))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.TASK_NOT_FOUND);
    }

    @Test
    void updateTaskValidationSucceedsWithValidTask() {
        UUID projectId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        AgentToolContext ctx = new AgentToolContext(
                UUID.randomUUID(), projectId, UUID.randomUUID(),
                "OWNER", false, 0);

        JsonNode arguments = json.createObjectNode()
                .put("taskId", taskId.toString());
        ObjectNode changes = json.createObjectNode()
                .put("title", "Updated Task")
                .put("version", 4);
        ((ObjectNode) arguments).set("changes", changes);

        TaskView taskView = mock(TaskView.class);
        when(taskView.version()).thenReturn(4);
        when(tasks.get(eq(projectId), eq(taskId), any())).thenReturn(taskView);

        // 不抛异常即为成功
        updateTaskTool.revalidate(ctx, arguments);
    }

    @Test
    void updateTaskValidationRejectsChangedVersion() {
        UUID projectId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        AgentToolContext ctx = new AgentToolContext(
                UUID.randomUUID(), projectId, UUID.randomUUID(),
                "OWNER", false, 0);
        ObjectNode arguments = json.createObjectNode().put("taskId", taskId.toString());
        arguments.set("changes", json.createObjectNode()
                .put("title", "Updated Task")
                .put("version", 3));

        TaskView taskView = mock(TaskView.class);
        when(taskView.version()).thenReturn(4);
        when(tasks.get(projectId, taskId, ctx.userId())).thenReturn(taskView);

        assertThatThrownBy(() -> updateTaskTool.revalidate(ctx, arguments))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue(
                        "errorCode", ErrorCode.AGENT_APPROVAL_VERSION_CONFLICT);
        verify(tasks, never()).update(any(), any(), any(), any());
    }

    // ========== create_milestone_after_approval ==========

    @Test
    void createMilestoneValidationFailsWhenProjectDeleted() {
        AgentToolContext ctx = new AgentToolContext(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "OWNER", false, 0);
        JsonNode arguments = json.createObjectNode()
                .put("name", "Sprint 1")
                .put("startDate", "2026-01-01")
                .put("endDate", "2026-01-15");

        when(projects.get(any(), any())).thenThrow(new BusinessException(ErrorCode.PROJECT_NOT_FOUND));

        assertThatThrownBy(() -> createMilestoneTool.revalidate(ctx, arguments))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.PROJECT_NOT_FOUND);
    }

    // ========== update_milestone_after_approval ==========

    @Test
    void updateMilestoneValidationFailsWhenMilestoneDeleted() {
        UUID projectId = UUID.randomUUID();
        UUID milestoneId = UUID.randomUUID();
        AgentToolContext ctx = new AgentToolContext(
                UUID.randomUUID(), projectId, UUID.randomUUID(),
                "OWNER", false, 0);

        JsonNode arguments = json.createObjectNode()
                .put("milestoneId", milestoneId.toString());
        ObjectNode changes = json.createObjectNode().put("name", "Updated Milestone");
        ((ObjectNode) arguments).set("changes", changes);

        when(milestones.list(eq(projectId), any())).thenReturn(List.of());

        assertThatThrownBy(() -> updateMilestoneTool.revalidate(ctx, arguments))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.AGENT_APPROVAL_REVALIDATION_FAILED);
    }

    @Test
    void updateMilestoneValidationSucceedsWithValidMilestone() {
        UUID projectId = UUID.randomUUID();
        UUID milestoneId = UUID.randomUUID();
        AgentToolContext ctx = new AgentToolContext(
                UUID.randomUUID(), projectId, UUID.randomUUID(),
                "OWNER", false, 0);

        JsonNode arguments = json.createObjectNode()
                .put("milestoneId", milestoneId.toString());
        ObjectNode changes = json.createObjectNode()
                .put("name", "Updated Milestone")
                .put("version", 2);
        ((ObjectNode) arguments).set("changes", changes);

        MilestoneView milestoneView = mock(MilestoneView.class);
        when(milestoneView.id()).thenReturn(milestoneId);
        when(milestoneView.version()).thenReturn(2);
        when(milestones.list(eq(projectId), any())).thenReturn(List.of(milestoneView));

        // 不抛异常即为成功
        updateMilestoneTool.revalidate(ctx, arguments);
    }

    @Test
    void updateMilestoneValidationRejectsChangedVersion() {
        UUID projectId = UUID.randomUUID();
        UUID milestoneId = UUID.randomUUID();
        AgentToolContext ctx = new AgentToolContext(
                UUID.randomUUID(), projectId, UUID.randomUUID(),
                "OWNER", false, 0);
        ObjectNode arguments = json.createObjectNode().put("milestoneId", milestoneId.toString());
        arguments.set("changes", json.createObjectNode()
                .put("name", "Updated Milestone")
                .put("version", 1));

        MilestoneView milestone = mock(MilestoneView.class);
        when(milestone.id()).thenReturn(milestoneId);
        when(milestone.version()).thenReturn(2);
        when(milestones.list(projectId, ctx.userId())).thenReturn(List.of(milestone));

        assertThatThrownBy(() -> updateMilestoneTool.revalidate(ctx, arguments))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue(
                        "errorCode", ErrorCode.AGENT_APPROVAL_VERSION_CONFLICT);
        verify(milestones, never()).update(any(), any(), any(), any());
    }
}
