package com.shitulelv.aicollab.work.application.service;

import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.project.application.service.AuditService;
import com.shitulelv.aicollab.project.domain.model.ProjectRole;
import com.shitulelv.aicollab.project.domain.policy.ProjectAccessGuard;
import com.shitulelv.aicollab.project.infrastructure.repository.ProjectMemberRepository;
import com.shitulelv.aicollab.work.api.dto.BatchUpdateTasksRequest;
import com.shitulelv.aicollab.work.api.dto.CreateTaskRequest;
import com.shitulelv.aicollab.work.api.dto.ReplaceDependenciesRequest;
import com.shitulelv.aicollab.work.api.dto.UpdateTaskRequest;
import com.shitulelv.aicollab.work.application.view.TaskView;
import com.shitulelv.aicollab.work.domain.model.TaskDependencyEdge;
import com.shitulelv.aicollab.work.domain.model.TaskPriority;
import com.shitulelv.aicollab.work.domain.model.TaskStatus;
import com.shitulelv.aicollab.work.domain.policy.TaskDependencyPolicy;
import com.shitulelv.aicollab.work.domain.policy.TaskStatusPolicy;
import com.shitulelv.aicollab.work.domain.policy.WorkPermissionPolicy;
import com.shitulelv.aicollab.work.infrastructure.entity.TaskEntity;
import com.shitulelv.aicollab.notification.application.service.NotificationApplicationService;
import com.shitulelv.aicollab.work.infrastructure.repository.MilestoneRepository;
import com.shitulelv.aicollab.work.infrastructure.repository.TaskRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class TaskApplicationService {
    private final ProjectAccessGuard access;
    private final ProjectMemberRepository members;
    private final MilestoneRepository milestones;
    private final TaskRepository tasks;
    private final WorkPermissionPolicy permissions;
    private final TaskStatusPolicy statuses;
    private final TaskDependencyPolicy dependencies;
    private final AuditService audit;
    private final NotificationApplicationService notifications;
    private final ProjectDateRangePolicy projectDates;

    public TaskApplicationService(
            ProjectAccessGuard access, ProjectMemberRepository members, MilestoneRepository milestones,
            TaskRepository tasks, WorkPermissionPolicy permissions, TaskStatusPolicy statuses,
            TaskDependencyPolicy dependencies, AuditService audit,
            NotificationApplicationService notifications,
            ProjectDateRangePolicy projectDates) {
        this.access = access;
        this.members = members;
        this.milestones = milestones;
        this.tasks = tasks;
        this.permissions = permissions;
        this.statuses = statuses;
        this.dependencies = dependencies;
        this.audit = audit;
        this.notifications = notifications;
        this.projectDates = projectDates;
    }

    @Transactional(readOnly = true)
    public List<TaskView> list(
            UUID projectId, TaskStatus status, UUID assigneeId, UUID milestoneId, UUID userId) {
        access.requireMember(projectId, userId);
        return tasks.list(projectId, status, assigneeId, milestoneId).stream()
                .map(this::view).toList();
    }

    @Transactional(readOnly = true)
    public TaskView get(UUID projectId, UUID taskId, UUID userId) {
        access.requireMember(projectId, userId);
        return view(requireTask(projectId, taskId));
    }

    @Transactional
    public TaskView create(UUID projectId, CreateTaskRequest request, UUID userId) {
        permissions.requireAdmin(access.requireMember(projectId, userId));
        validateDates(request.startDate(), request.dueDate());
        projectDates.validate(projectId, request.startDate(), request.dueDate(), null);
        validateReferences(projectId, request.milestoneId(), request.assigneeId());
        TaskEntity entity = new TaskEntity();
        entity.setId(UUID.randomUUID());
        entity.setProjectId(projectId);
        entity.setTitle(request.title().trim());
        entity.setDescription(request.description() == null ? "" : request.description());
        entity.setMilestoneId(request.milestoneId());
        entity.setAssigneeId(request.assigneeId());
        entity.setStatus(request.status() == null ? TaskStatus.TODO : request.status());
        entity.setPriority(request.priority() == null ? TaskPriority.MEDIUM : request.priority());
        entity.setEstimateHours(request.estimateHours());
        entity.setStartDate(request.startDate());
        entity.setDueDate(request.dueDate());
        entity.setSortOrder(0);
        entity.setCreatedBy(userId);
        entity.setVersion(0);
        tasks.create(entity);
        audit.write(projectId, userId, "TASK_CREATED", "TASK", entity.getId(),
                Map.of("title", entity.getTitle(), "status", entity.getStatus().name(),
                        "priority", entity.getPriority().name()));
        if (entity.getAssigneeId() != null && !entity.getAssigneeId().equals(userId)) {
            notifications.create(projectId, entity.getAssigneeId(),
                    "TASK_ASSIGNED", "你被分配了新任务",
                    "任务「" + entity.getTitle() + "」已分配给你",
                    "TASK", entity.getId());
        }
        return view(requireTask(projectId, entity.getId()));
    }

    @Transactional
    public TaskView update(UUID projectId, UUID taskId, UpdateTaskRequest request, UUID userId) {
        ProjectRole role = access.requireMember(projectId, userId);
        TaskEntity current = requireTask(projectId, taskId);
        TaskStatus previousStatus = current.getStatus();
        permissions.validateTaskUpdate(role, userId, current, request);
        TaskStatus targetStatus = request.status() == null ? current.getStatus() : request.status();
        statuses.validateTransition(current.getStatus(), targetStatus, current.getUnfinishedDependencyCount());

        if (role.isAdminOrOwner()) {
            if (request.title() == null || request.status() == null || request.priority() == null) {
                throw new BusinessException(
                        ErrorCode.VALIDATION_ERROR, "管理员更新必须提供 title、status 和 priority");
            }
            validateDates(request.startDate(), request.dueDate());
            projectDates.validate(projectId, request.startDate(), request.dueDate(), null);
            validateReferences(projectId, request.milestoneId(), request.assigneeId());
            current.setTitle(request.title().trim());
            current.setDescription(request.description() == null ? "" : request.description());
            current.setMilestoneId(request.milestoneId());
            current.setAssigneeId(request.assigneeId());
            current.setPriority(request.priority());
            current.setEstimateHours(request.estimateHours());
            current.setStartDate(request.startDate());
            current.setDueDate(request.dueDate());
        }
        current.setStatus(targetStatus);
        current.setVersion(request.version());
        if (!tasks.update(projectId, current)) {
            throw new BusinessException(ErrorCode.VERSION_CONFLICT);
        }
        audit.write(projectId, userId,
                previousStatus == targetStatus ? "TASK_UPDATED" : "TASK_STATUS_CHANGED",
                "TASK", taskId,
                Map.of("title", current.getTitle(),
                        "previousStatus", previousStatus.name(),
                        "newStatus", targetStatus.name()));
        if (previousStatus != targetStatus) {
            if (targetStatus == TaskStatus.DONE) {
                notifyDependents(projectId, taskId, current.getTitle(), userId);
            }
            if (current.getAssigneeId() != null && !current.getAssigneeId().equals(userId)) {
                notifications.create(projectId, current.getAssigneeId(),
                        "TASK_STATUS_CHANGED", "任务状态已变更",
                        "任务「" + current.getTitle() + "」状态变更为 " + targetStatus.name(),
                        "TASK", taskId);
            }
        }
        if (request.assigneeId() != null && !request.assigneeId().equals(current.getAssigneeId())
                && !request.assigneeId().equals(userId)) {
            notifications.create(projectId, request.assigneeId(),
                    "TASK_ASSIGNED", "你被分配了新任务",
                    "任务「" + current.getTitle() + "」已分配给你",
                    "TASK", taskId);
        }
        return view(requireTask(projectId, taskId));
    }

    @Transactional
    public void delete(UUID projectId, UUID taskId, UUID userId) {
        permissions.requireAdmin(access.requireMember(projectId, userId));
        if (!tasks.delete(projectId, taskId)) {
            throw new BusinessException(ErrorCode.TASK_NOT_FOUND);
        }
        audit.write(projectId, userId, "TASK_DELETED", "TASK", taskId,
                Map.of("title", "已删除"));
    }

    @Transactional
    public TaskView replaceDependencies(
            UUID projectId, UUID taskId, ReplaceDependenciesRequest request, UUID userId) {
        permissions.requireAdmin(access.requireMember(projectId, userId));
        Set<UUID> nodes = new HashSet<>(tasks.lockProjectTaskIds(projectId));
        if (!nodes.contains(taskId)) {
            throw new BusinessException(ErrorCode.TASK_NOT_FOUND);
        }
        List<UUID> requested = List.copyOf(request.dependencyIds());
        if (new HashSet<>(requested).size() != requested.size()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "依赖任务不能重复");
        }
        if (requested.contains(taskId)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "任务不能依赖自身");
        }
        if (!nodes.containsAll(requested)) {
            throw new BusinessException(ErrorCode.TASK_DEPENDENCY_CROSS_PROJECT);
        }
        Map<UUID, List<UUID>> graph = new HashMap<>();
        nodes.forEach(node -> graph.put(node, new ArrayList<>()));
        for (TaskDependencyEdge edge : tasks.listEdges(projectId)) {
            graph.get(edge.taskId()).add(edge.dependsOnTaskId());
        }
        graph.put(taskId, requested);
        dependencies.validateAcyclic(nodes, graph);
        tasks.replaceDependencies(projectId, taskId, requested);
        TaskView refreshed = view(requireTask(projectId, taskId));
        audit.write(projectId, userId, "TASK_DEPENDENCIES_REPLACED", "TASK", taskId);
        return refreshed;
    }

    private TaskEntity requireTask(UUID projectId, UUID taskId) {
        return tasks.find(projectId, taskId)
                .orElseThrow(() -> new BusinessException(ErrorCode.TASK_NOT_FOUND));
    }

    private TaskView view(TaskEntity entity) {
        return TaskView.from(entity, tasks.dependencyIds(entity.getProjectId(), entity.getId()));
    }

    private void validateReferences(UUID projectId, UUID milestoneId, UUID assigneeId) {
        if (milestoneId != null && milestones.find(projectId, milestoneId).isEmpty()) {
            throw new BusinessException(ErrorCode.TASK_MILESTONE_CROSS_PROJECT);
        }
        if (assigneeId != null && members.findRole(projectId, assigneeId).isEmpty()) {
            throw new BusinessException(ErrorCode.TASK_ASSIGNEE_NOT_MEMBER);
        }
    }

    private static void validateDates(java.time.LocalDate startDate, java.time.LocalDate dueDate) {
        if (startDate != null && dueDate != null && startDate.isAfter(dueDate)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "startDate 不能晚于 dueDate");
        }
    }

    private void notifyDependents(UUID projectId, UUID completedTaskId, String completedTitle, UUID actorId) {
        List<TaskEntity> dependents = tasks.findDependents(projectId, completedTaskId);
        for (TaskEntity dependent : dependents) {
            if (dependent.getAssigneeId() != null && !dependent.getAssigneeId().equals(actorId)) {
                notifications.create(projectId, dependent.getAssigneeId(),
                        "DEPENDENCY_COMPLETED", "前置任务已完成",
                        "任务「" + completedTitle + "」已完成，你可以开始「" + dependent.getTitle() + "」",
                        "TASK", dependent.getId());
            }
        }
    }

    @Transactional
    public List<TaskView> batchUpdate(UUID projectId, BatchUpdateTasksRequest request, UUID userId) {
        permissions.requireAdmin(access.requireMember(projectId, userId));
        List<TaskView> results = new ArrayList<>();
        for (BatchUpdateTasksRequest.TaskUpdateItem item : request.items()) {
            TaskEntity current = requireTask(projectId, item.taskId());
            if (item.status() != null) {
                statuses.validateTransition(current.getStatus(), item.status(), current.getUnfinishedDependencyCount());
                current.setStatus(item.status());
            }
            if (item.sortOrder() != null) {
                current.setSortOrder(item.sortOrder());
            }
            if (item.priority() != null) {
                current.setPriority(item.priority());
            }
            if (item.assigneeId() != null) {
                validateReferences(projectId, null, item.assigneeId());
                current.setAssigneeId(item.assigneeId());
            }
            current.setVersion(item.version());
            if (!tasks.update(projectId, current)) {
                throw new BusinessException(ErrorCode.VERSION_CONFLICT);
            }
            if (item.assigneeId() != null && !item.assigneeId().equals(userId)) {
                notifications.create(projectId, item.assigneeId(),
                        "TASK_ASSIGNED", "你被分配了新任务",
                        "任务「" + current.getTitle() + "」已分配给你",
                        "TASK", item.taskId());
            }
            results.add(view(requireTask(projectId, item.taskId())));
        }
        audit.write(projectId, userId, "TASKS_BATCH_UPDATED", "TASK", null,
                Map.of("taskCount", request.items().size()));
        return results;
    }
}
