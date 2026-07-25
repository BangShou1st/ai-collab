package com.shitulelv.aicollab.work.application.service;

import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.project.application.service.AuditService;
import com.shitulelv.aicollab.project.domain.model.ProjectRole;
import com.shitulelv.aicollab.project.domain.policy.ProjectAccessGuard;
import com.shitulelv.aicollab.work.api.dto.CreateCommentRequest;
import com.shitulelv.aicollab.work.api.dto.UpdateCommentRequest;
import com.shitulelv.aicollab.work.application.view.TaskCommentView;
import com.shitulelv.aicollab.work.infrastructure.entity.TaskCommentEntity;
import com.shitulelv.aicollab.work.infrastructure.repository.TaskCommentRepository;
import com.shitulelv.aicollab.work.infrastructure.repository.TaskRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class TaskCommentApplicationService {
    private final ProjectAccessGuard access;
    private final TaskRepository tasks;
    private final TaskCommentRepository comments;
    private final AuditService audit;

    public TaskCommentApplicationService(
            ProjectAccessGuard access, TaskRepository tasks,
            TaskCommentRepository comments, AuditService audit) {
        this.access = access;
        this.tasks = tasks;
        this.comments = comments;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<TaskCommentView> list(UUID projectId, UUID taskId, UUID userId) {
        access.requireMember(projectId, userId);
        requireTask(projectId, taskId);
        return comments.list(projectId, taskId).stream().map(TaskCommentView::from).toList();
    }

    @Transactional
    public TaskCommentView create(
            UUID projectId, UUID taskId, CreateCommentRequest request, UUID userId) {
        access.requireMember(projectId, userId);
        requireTask(projectId, taskId);
        TaskCommentEntity entity = new TaskCommentEntity();
        entity.setId(UUID.randomUUID());
        entity.setProjectId(projectId);
        entity.setTaskId(taskId);
        entity.setAuthorId(userId);
        entity.setContent(request.content().trim());
        comments.create(entity);
        audit.write(projectId, userId, "TASK_COMMENT_CREATED", "TASK_COMMENT", entity.getId());
        return TaskCommentView.from(comments.find(projectId, taskId, entity.getId()).orElseThrow());
    }

    @Transactional
    public TaskCommentView update(
            UUID projectId, UUID taskId, UUID commentId, UpdateCommentRequest request, UUID userId) {
        access.requireMember(projectId, userId);
        requireTask(projectId, taskId);
        TaskCommentEntity existing = requireComment(projectId, taskId, commentId);
        if (!existing.getAuthorId().equals(userId)) {
            throw new BusinessException(ErrorCode.COMMENT_AUTHOR_REQUIRED);
        }
        if (!comments.updateByAuthor(projectId, taskId, commentId, userId, request.content().trim())) {
            throw new BusinessException(ErrorCode.COMMENT_NOT_FOUND);
        }
        audit.write(projectId, userId, "TASK_COMMENT_UPDATED", "TASK_COMMENT", commentId);
        return TaskCommentView.from(requireComment(projectId, taskId, commentId));
    }

    @Transactional
    public void delete(UUID projectId, UUID taskId, UUID commentId, UUID userId) {
        ProjectRole role = access.requireMember(projectId, userId);
        requireTask(projectId, taskId);
        TaskCommentEntity existing = requireComment(projectId, taskId, commentId);
        if (!existing.getAuthorId().equals(userId) && !role.isAdminOrOwner()) {
            throw new BusinessException(ErrorCode.AUTH_FORBIDDEN);
        }
        if (!comments.delete(projectId, taskId, commentId)) {
            throw new BusinessException(ErrorCode.COMMENT_NOT_FOUND);
        }
        audit.write(projectId, userId, "TASK_COMMENT_DELETED", "TASK_COMMENT", commentId);
    }

    private void requireTask(UUID projectId, UUID taskId) {
        if (tasks.find(projectId, taskId).isEmpty()) {
            throw new BusinessException(ErrorCode.TASK_NOT_FOUND);
        }
    }

    private TaskCommentEntity requireComment(UUID projectId, UUID taskId, UUID commentId) {
        return comments.find(projectId, taskId, commentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.COMMENT_NOT_FOUND));
    }
}
