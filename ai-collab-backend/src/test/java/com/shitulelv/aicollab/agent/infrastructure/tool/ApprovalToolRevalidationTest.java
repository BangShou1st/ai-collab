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
import com.shitulelv.aicollab.project.infrastructure.repository.ProjectMemberRepository;
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
    @Mock
    private ProjectMemberRepository members;

    private CreateTaskApprovalAgentTool createTaskTool;
    private UpdateTaskApprovalAgentTool updateTaskTool;
    private CreateMilestoneApprovalAgentTool createMilestoneTool;
    private UpdateMilestoneApprovalAgentTool updateMilestoneTool;

    @BeforeEach
    void setUp() {
        createTaskTool = new CreateTaskApprovalAgentTool(json, validator, tasks, projects, members);
        updateTaskTool = new UpdateTaskApprovalAgentTool(json, validator, tasks, members);
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

    @Test
    void normalizedCreateProposalCanBeRevisedRevalidatedAndExecutedWithDisplayMetadata() {
        var ctx = new AgentToolContext(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "OWNER", false, 0);
        UUID assignee = UUID.randomUUID();
        var member = mock(com.shitulelv.aicollab.project.application.view.MemberView.class);
        when(member.displayName()).thenReturn("Current Owner");
        when(members.find(ctx.projectId(), assignee)).thenReturn(java.util.Optional.of(member));
        var stored = json.createObjectNode().put("title", "Original").put("estimateHours", 2)
                .put("assigneeId", assignee.toString()).put("assigneeName", "Old display name");
        var merged = createTaskTool.mergeArguments(stored, json.createObjectNode().put("title", "Revised").put("estimateHours", 3));
        var normalized = createTaskTool.normalize(ctx, merged);
        assertThat(normalized.path("assigneeName").asText()).isEqualTo("Current Owner");
        assertThat(stored.path("title").asText()).isEqualTo("Original");
        createTaskTool.revalidate(ctx, normalized);
        createTaskTool.execute(ctx, normalized);
        var request = org.mockito.ArgumentCaptor.forClass(com.shitulelv.aicollab.work.api.dto.CreateTaskRequest.class);
        verify(tasks).create(eq(ctx.projectId()), request.capture(), eq(ctx.userId()));
        assertThat(request.getValue().title()).isEqualTo("Revised");
        assertThat(request.getValue().estimateHours()).isEqualByComparingTo("3");
        assertThat(request.getValue().assigneeId()).isEqualTo(assignee);
    }

    @Test
    void normalizedUpdateProposalKeepsDisplayMetadataOutOfBusinessRequest() {
        var ctx = new AgentToolContext(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "OWNER", false, 0);
        UUID taskId = UUID.randomUUID();
        var normalized = json.createObjectNode().put("taskId", taskId.toString());
        normalized.set("changes", json.createObjectNode().put("title", "Revised").put("estimateHours", 3)
                .put("version", 2).put("assigneeName", "Display only"));
        var task = mock(TaskView.class);
        when(task.version()).thenReturn(2);
        when(tasks.get(ctx.projectId(), taskId, ctx.userId())).thenReturn(task);
        updateTaskTool.normalize(ctx, normalized);
        updateTaskTool.revalidate(ctx, normalized);
        updateTaskTool.execute(ctx, normalized);
        var request = org.mockito.ArgumentCaptor.forClass(com.shitulelv.aicollab.work.api.dto.UpdateTaskRequest.class);
        verify(tasks).update(eq(ctx.projectId()), eq(taskId), request.capture(), eq(ctx.userId()));
        assertThat(request.getValue().title()).isEqualTo("Revised");
        assertThat(request.getValue().version()).isEqualTo(2);
    }

    @Test
    void displayMetadataHandlingDoesNotAllowOtherUnknownFields() {
        var ctx = new AgentToolContext(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "OWNER", false, 0);
        var arguments = json.createObjectNode().put("title", "Test").put("assigneeName", "Display")
                .put("bypassApproval", true);
        assertThatThrownBy(() -> createTaskTool.normalize(ctx, arguments)).isInstanceOf(IllegalArgumentException.class);
        verify(tasks, never()).create(any(), any(), any());
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
