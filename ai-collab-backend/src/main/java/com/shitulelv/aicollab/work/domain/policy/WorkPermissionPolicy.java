package com.shitulelv.aicollab.work.domain.policy;

import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.project.domain.model.ProjectRole;
import com.shitulelv.aicollab.work.api.dto.UpdateTaskRequest;
import com.shitulelv.aicollab.work.infrastructure.entity.TaskEntity;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class WorkPermissionPolicy {
    public void requireAdmin(ProjectRole role) {
        if (!role.isAdminOrOwner()) {
            throw new BusinessException(ErrorCode.PROJECT_ADMIN_REQUIRED);
        }
    }

    public void validateTaskUpdate(
            ProjectRole role, UUID userId, TaskEntity current, UpdateTaskRequest request) {
        if (role.isAdminOrOwner()) {
            return;
        }
        if (!userId.equals(current.getAssigneeId())) {
            throw new BusinessException(ErrorCode.AUTH_FORBIDDEN);
        }
        if (request.status() == null
                || request.title() != null
                || request.description() != null
                || request.milestoneId() != null
                || request.assigneeId() != null
                || request.priority() != null
                || request.estimateHours() != null
                || request.startDate() != null
                || request.dueDate() != null) {
            throw new BusinessException(ErrorCode.AUTH_FORBIDDEN);
        }
    }
}
