package com.shitulelv.aicollab.agent.application.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.agent.application.view.AgentRunView;
import com.shitulelv.aicollab.agent.domain.model.AgentExecutionContext;
import com.shitulelv.aicollab.agent.domain.model.AgentPageContext;
import com.shitulelv.aicollab.agent.domain.model.AgentRunStatus;
import com.shitulelv.aicollab.agent.infrastructure.repository.AgentApprovalRepository;
import com.shitulelv.aicollab.agent.infrastructure.repository.AgentRepository;
import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.document.application.service.DocumentApplicationService;
import com.shitulelv.aicollab.document.application.view.DocumentView;
import com.shitulelv.aicollab.project.domain.model.ProjectRole;
import com.shitulelv.aicollab.project.domain.policy.ProjectAccessGuard;
import com.shitulelv.aicollab.work.application.service.MilestoneApplicationService;
import com.shitulelv.aicollab.work.application.service.TaskApplicationService;
import com.shitulelv.aicollab.work.application.view.MilestoneView;
import com.shitulelv.aicollab.work.application.view.TaskView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * AgentContextAssembler 测试。
 * 覆盖：合法/跨项目/不存在/权限不足场景，以及 Application Service 异常不被吞掉。
 * <p>
 * 关键行为变化：用户明确提交的实体 ID 不存在或跨项目时，
 * 必须抛出 AGENT_CONTEXT_RESOURCE_INVALID，不得静默转换为 null。
 */
class AgentContextAssemblerTest {
    private final ObjectMapper json = new ObjectMapper();
    private ProjectAccessGuard access;
    private AgentRepository repository;
    private AgentApprovalRepository approvals;
    private TaskApplicationService tasks;
    private DocumentApplicationService documents;
    private MilestoneApplicationService milestones;
    private AgentContextAssembler assembler;

    private final UUID projectId = UUID.randomUUID();
    private final UUID requesterId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        access = mock(ProjectAccessGuard.class);
        repository = mock(AgentRepository.class);
        approvals = mock(AgentApprovalRepository.class);
        tasks = mock(TaskApplicationService.class);
        documents = mock(DocumentApplicationService.class);
        milestones = mock(MilestoneApplicationService.class);
        assembler = new AgentContextAssembler(access, repository, approvals, tasks, documents, milestones, json);

        when(access.requireMember(projectId, requesterId)).thenReturn(ProjectRole.MEMBER);
    }

    // ========== Task 测试 ==========

    @Test
    void validTaskIsPreserved() {
        UUID taskId = UUID.randomUUID();
        when(tasks.get(projectId, taskId, requesterId)).thenReturn(mock(TaskView.class));

        AgentPageContext page = new AgentPageContext("TASK_BOARD", taskId, null, null, null);
        AgentExecutionContext ctx = assembler.assemble(run(), null, page);

        assertThat(ctx.page().selectedTaskId()).isEqualTo(taskId);
    }

    @Test
    void nonExistentTaskThrows() {
        UUID taskId = UUID.randomUUID();
        when(tasks.get(projectId, taskId, requesterId))
                .thenThrow(new BusinessException(ErrorCode.TASK_NOT_FOUND));

        AgentPageContext page = new AgentPageContext("TASK_BOARD", taskId, null, null, null);

        assertThatThrownBy(() -> assembler.assemble(run(), null, page))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.AGENT_CONTEXT_RESOURCE_INVALID));
    }

    @Test
    void crossProjectTaskThrows() {
        UUID taskId = UUID.randomUUID();
        // 跨项目：tasks.get 抛出 TASK_NOT_FOUND（因为 projectId 不匹配）
        when(tasks.get(projectId, taskId, requesterId))
                .thenThrow(new BusinessException(ErrorCode.TASK_NOT_FOUND));

        AgentPageContext page = new AgentPageContext("TASK_BOARD", taskId, null, null, null);

        assertThatThrownBy(() -> assembler.assemble(run(), null, page))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.AGENT_CONTEXT_RESOURCE_INVALID));
    }

    @Test
    void permissionDeniedTaskThrows() {
        UUID taskId = UUID.randomUUID();
        when(tasks.get(projectId, taskId, requesterId))
                .thenThrow(new BusinessException(ErrorCode.AUTH_FORBIDDEN));

        AgentPageContext page = new AgentPageContext("TASK_BOARD", taskId, null, null, null);

        assertThatThrownBy(() -> assembler.assemble(run(), null, page))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.AUTH_FORBIDDEN));
    }

    // ========== Milestone 测试 ==========

    @Test
    void validMilestoneIsPreserved() {
        UUID milestoneId = UUID.randomUUID();
        when(milestones.get(projectId, milestoneId, requesterId))
                .thenReturn(mock(MilestoneView.class));

        AgentPageContext page = new AgentPageContext("MILESTONE_LIST", null, milestoneId, null, null);
        AgentExecutionContext ctx = assembler.assemble(run(), null, page);

        assertThat(ctx.page().selectedMilestoneId()).isEqualTo(milestoneId);
    }

    @Test
    void nonExistentMilestoneThrows() {
        UUID milestoneId = UUID.randomUUID();
        when(milestones.get(projectId, milestoneId, requesterId))
                .thenThrow(new BusinessException(ErrorCode.MILESTONE_NOT_FOUND));

        AgentPageContext page = new AgentPageContext("MILESTONE_LIST", null, milestoneId, null, null);

        assertThatThrownBy(() -> assembler.assemble(run(), null, page))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.AGENT_CONTEXT_RESOURCE_INVALID));
    }

    @Test
    void crossProjectMilestoneThrows() {
        UUID milestoneId = UUID.randomUUID();
        when(milestones.get(projectId, milestoneId, requesterId))
                .thenThrow(new BusinessException(ErrorCode.MILESTONE_NOT_FOUND));

        AgentPageContext page = new AgentPageContext("MILESTONE_LIST", null, milestoneId, null, null);

        assertThatThrownBy(() -> assembler.assemble(run(), null, page))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.AGENT_CONTEXT_RESOURCE_INVALID));
    }

    // ========== Document 测试 ==========

    @Test
    void validDocumentIsPreserved() {
        UUID documentId = UUID.randomUUID();
        when(documents.get(projectId, documentId, requesterId))
                .thenReturn(mock(DocumentView.class));

        AgentPageContext page = new AgentPageContext("DOCUMENT_DETAIL", null, null, documentId, null);
        AgentExecutionContext ctx = assembler.assemble(run(), null, page);

        assertThat(ctx.page().selectedDocumentId()).isEqualTo(documentId);
    }

    @Test
    void nonExistentDocumentThrows() {
        UUID documentId = UUID.randomUUID();
        when(documents.get(projectId, documentId, requesterId))
                .thenThrow(new BusinessException(ErrorCode.DOCUMENT_NOT_FOUND));

        AgentPageContext page = new AgentPageContext("DOCUMENT_DETAIL", null, null, documentId, null);

        assertThatThrownBy(() -> assembler.assemble(run(), null, page))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.AGENT_CONTEXT_RESOURCE_INVALID));
    }

    @Test
    void crossProjectDocumentThrows() {
        UUID documentId = UUID.randomUUID();
        when(documents.get(projectId, documentId, requesterId))
                .thenThrow(new BusinessException(ErrorCode.DOCUMENT_NOT_FOUND));

        AgentPageContext page = new AgentPageContext("DOCUMENT_DETAIL", null, null, documentId, null);

        assertThatThrownBy(() -> assembler.assemble(run(), null, page))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.AGENT_CONTEXT_RESOURCE_INVALID));
    }

    // ========== 未提供实体 ID 测试 ==========

    @Test
    void nullTaskIdIsAllowed() {
        AgentPageContext page = new AgentPageContext("TASK_BOARD", null, null, null, null);
        AgentExecutionContext ctx = assembler.assemble(run(), null, page);

        assertThat(ctx.page().selectedTaskId()).isNull();
    }

    // ========== 异常传播测试 ==========

    @Test
    void applicationServiceInternalExceptionNotSwallowed() {
        UUID taskId = UUID.randomUUID();
        when(tasks.get(projectId, taskId, requesterId))
                .thenThrow(new RuntimeException("数据库连接失败"));

        AgentPageContext page = new AgentPageContext("TASK_BOARD", taskId, null, null, null);

        assertThatThrownBy(() -> assembler.assemble(run(), null, page))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("数据库连接失败");
    }

    // ========== 辅助方法 ==========

    private AgentRunView run() {
        OffsetDateTime now = OffsetDateTime.now();
        return new AgentRunView(
                UUID.randomUUID(), UUID.randomUUID(), projectId, requesterId,
                null, "MEMBER", 0, "检查项目", AgentRunStatus.RUNNING,
                12, 8, 3, 50_000, 20_000,
                0, 0, 0, 0, 0, false,
                false, false, 0, null, null, null, null, 1, now, now);
    }
}
