package com.shitulelv.aicollab.work.application.service;

import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.project.application.service.AuditService;
import com.shitulelv.aicollab.project.domain.model.ProjectRole;
import com.shitulelv.aicollab.project.domain.policy.ProjectAccessGuard;
import com.shitulelv.aicollab.project.infrastructure.repository.ProjectMemberRepository;
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

    public TaskApplicationService(
            ProjectAccessGuard access, ProjectMemberRepository members, MilestoneRepository milestones,
            TaskRepository tasks, WorkPermissionPolicy permissions, TaskStatusPolicy statuses,
            TaskDependencyPolicy dependencies, AuditService audit) {
        this.access = access;
        this.members = members;
        this.milestones = milestones;
        this.tasks = tasks;
        this.permissions = permissions;
        this.statuses = statuses;
        this.dependencies = dependencies;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<TaskView> list(
            UUID projectId, TaskStatus status, UUID assigneeId, UUID milestoneId, UUID userId) {
        access.requireMember(projectId, userId);
        return tasks.list(projectId, status, assigneeId, milestoneId).stream()
                .map(task -> TaskView.from(task, List.of())).toList();
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
        audit.write(projectId, userId, "TASK_CREATED", "TASK", entity.getId());
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
                "TASK", taskId);
        return view(requireTask(projectId, taskId));
    }

    @Transactional
    public void delete(UUID projectId, UUID taskId, UUID userId) {
        permissions.requireAdmin(access.requireMember(projectId, userId));
        if (!tasks.delete(projectId, taskId)) {
            throw new BusinessException(ErrorCode.TASK_NOT_FOUND);
        }
        audit.write(projectId, userId, "TASK_DELETED", "TASK", taskId);
    }

    @Transactional
    public TaskView replaceDependencies(
            UUID projectId, UUID taskId, ReplaceDependenciesRequest request, UUID userId) {
        permissions.requireAdmin(access.requireMember(projectId, userId));
        requireTask(projectId, taskId);
        List<UUID> requested = List.copyOf(request.dependencyIds());
        if (new HashSet<>(requested).size() != requested.size()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "依赖任务不能重复");
        }
        if (requested.contains(taskId)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "任务不能依赖自身");
        }
        Set<UUID> nodes = new HashSet<>(tasks.listIds(projectId));
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
        audit.write(projectId, userId, "TASK_DEPENDENCIES_REPLACED", "TASK", taskId);
        return view(requireTask(projectId, taskId));
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
}
