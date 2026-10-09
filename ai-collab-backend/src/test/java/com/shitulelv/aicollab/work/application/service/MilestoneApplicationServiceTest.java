package com.shitulelv.aicollab.work.application.service;

import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.project.application.service.AuditService;
import com.shitulelv.aicollab.project.domain.model.ProjectRole;
import com.shitulelv.aicollab.project.domain.policy.ProjectAccessGuard;
import com.shitulelv.aicollab.work.api.dto.CreateMilestoneRequest;
import com.shitulelv.aicollab.work.domain.model.MilestoneStatus;
import com.shitulelv.aicollab.work.domain.policy.WorkPermissionPolicy;
import com.shitulelv.aicollab.work.infrastructure.entity.MilestoneEntity;
import com.shitulelv.aicollab.work.infrastructure.repository.MilestoneRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MilestoneApplicationServiceTest {

    private static final LocalDate START = LocalDate.parse("2026-10-01");
    private static final LocalDate END = LocalDate.parse("2026-10-31");

    private ProjectAccessGuard access;
    private WorkPermissionPolicy permissions;
    private MilestoneRepository milestones;
    private AuditService audit;
    private ProjectDateRangePolicy projectDates;
    private MilestoneApplicationService service;

    private final UUID projectId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        access = mock(ProjectAccessGuard.class);
        permissions = new WorkPermissionPolicy();
        milestones = mock(MilestoneRepository.class);
        audit = mock(AuditService.class);
        projectDates = mock(ProjectDateRangePolicy.class);
        service = new MilestoneApplicationService(
                access, permissions, milestones, audit, projectDates);
        when(access.requireMember(projectId, userId)).thenReturn(ProjectRole.ADMIN);
    }

    private CreateMilestoneRequest createRequest() {
        return new CreateMilestoneRequest("里程碑A", "说明", START, END, END, null, null);
    }

    @Test
    void create_defaults_status_to_planned_and_writes_audit() {
        MilestoneEntity saved = new MilestoneEntity();
        saved.setId(UUID.randomUUID());
        saved.setProjectId(projectId);
        saved.setName("里程碑A");
        saved.setStatus(MilestoneStatus.PLANNED);
        saved.setSortOrder(0);
        saved.setVersion(0);
        saved.setStartDate(START);
        saved.setEndDate(END);
        when(milestones.find(eq(projectId), any(UUID.class))).thenReturn(Optional.of(saved));

        service.create(projectId, createRequest(), userId);

        verify(milestones).create(any(MilestoneEntity.class));
        verify(audit).write(eq(projectId), eq(userId), eq("MILESTONE_CREATED"), eq("MILESTONE"),
                any(UUID.class), any());
    }

    @Test
    void create_rejects_start_date_after_end_date() {
        CreateMilestoneRequest request = new CreateMilestoneRequest(
                "里程碑A", null, END, START, null, null, null);

        assertThatThrownBy(() -> service.create(projectId, request, userId))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getErrorCode())
                        .isEqualTo(ErrorCode.VALIDATION_ERROR));
        verify(milestones, never()).create(any());
    }

    @Test
    void delete_missing_milestone_reports_not_found() {
        when(milestones.delete(projectId, UUID.randomUUID())).thenReturn(false);

        assertThatThrownBy(() -> service.delete(projectId, UUID.randomUUID(), userId))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getErrorCode())
                        .isEqualTo(ErrorCode.MILESTONE_NOT_FOUND));
    }

    @Test
    void delete_existing_milestone_writes_audit() {
        UUID milestoneId = UUID.randomUUID();
        when(milestones.delete(projectId, milestoneId)).thenReturn(true);

        service.delete(projectId, milestoneId, userId);

        verify(audit).write(projectId, userId, "MILESTONE_DELETED", "MILESTONE", milestoneId);
    }

    @Test
    void get_missing_milestone_reports_not_found() {
        when(milestones.find(projectId, UUID.randomUUID())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(projectId, UUID.randomUUID(), userId))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getErrorCode())
                        .isEqualTo(ErrorCode.MILESTONE_NOT_FOUND));
    }
}
