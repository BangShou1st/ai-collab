package com.shitulelv.aicollab.work.application.service;

import com.shitulelv.aicollab.notification.application.service.NotificationApplicationService;
import com.shitulelv.aicollab.project.application.service.AuditService;
import com.shitulelv.aicollab.project.application.service.DashboardCacheService;
import com.shitulelv.aicollab.project.domain.model.ProjectRole;
import com.shitulelv.aicollab.project.domain.policy.ProjectAccessGuard;
import com.shitulelv.aicollab.project.infrastructure.repository.ProjectMemberRepository;
import com.shitulelv.aicollab.work.api.dto.UpdateTaskRequest;
import com.shitulelv.aicollab.work.domain.model.TaskPriority;
import com.shitulelv.aicollab.work.domain.model.TaskStatus;
import com.shitulelv.aicollab.work.domain.policy.TaskDependencyPolicy;
import com.shitulelv.aicollab.work.domain.policy.TaskStatusPolicy;
import com.shitulelv.aicollab.work.domain.policy.WorkPermissionPolicy;
import com.shitulelv.aicollab.work.infrastructure.entity.TaskEntity;
import com.shitulelv.aicollab.work.infrastructure.repository.MilestoneRepository;
import com.shitulelv.aicollab.work.infrastructure.repository.TaskRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TaskApplicationServiceNotificationTest {

    @Test
    void reassigningTaskNotifiesNewAssignee() {
        UUID projectId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        UUID previousAssignee = UUID.randomUUID();
        UUID newAssignee = UUID.randomUUID();

        ProjectAccessGuard access = mock(ProjectAccessGuard.class);
        ProjectMemberRepository members = mock(ProjectMemberRepository.class);
        TaskRepository tasks = mock(TaskRepository.class);
        NotificationApplicationService notifications = mock(NotificationApplicationService.class);
        ProjectDateRangePolicy projectDates = mock(ProjectDateRangePolicy.class);
        TaskApplicationService service = new TaskApplicationService(
                access,
                members,
                mock(MilestoneRepository.class),
                tasks,
                new WorkPermissionPolicy(),
                new TaskStatusPolicy(),
                mock(TaskDependencyPolicy.class),
                mock(AuditService.class),
                notifications,
                projectDates,
                mock(DashboardCacheService.class));

        TaskEntity task = new TaskEntity();
        task.setId(taskId);
        task.setProjectId(projectId);
        task.setTitle("验收任务");
        task.setDescription("");
        task.setStatus(TaskStatus.TODO);
        task.setPriority(TaskPriority.MEDIUM);
        task.setAssigneeId(previousAssignee);
        task.setVersion(0);
        task.setUnfinishedDependencyCount(0);

        when(access.requireMember(projectId, actorId)).thenReturn(ProjectRole.ADMIN);
        when(tasks.find(projectId, taskId)).thenReturn(Optional.of(task));
        when(tasks.update(projectId, task)).thenReturn(true);
        when(tasks.dependencyIds(projectId, taskId)).thenReturn(List.of());
        when(members.findRole(projectId, newAssignee)).thenReturn(Optional.of(ProjectRole.MEMBER));

        UpdateTaskRequest request = new UpdateTaskRequest(
                "验收任务",
                "",
                null,
                newAssignee,
                TaskStatus.TODO,
                TaskPriority.HIGH,
                null,
                LocalDate.of(2026, 11, 15),
                LocalDate.of(2026, 11, 20),
                0);

        service.update(projectId, taskId, request, actorId);

        verify(notifications).create(
                eq(projectId),
                eq(newAssignee),
                eq("TASK_ASSIGNED"),
                anyString(),
                contains("验收任务"),
                eq("TASK"),
                eq(taskId));
    }
}
