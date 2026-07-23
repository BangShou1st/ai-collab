package com.shitulelv.aicollab.project.domain.policy;

import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.project.domain.model.ProjectRole;
import com.shitulelv.aicollab.project.infrastructure.repository.ProjectMemberRepository;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class DatabaseProjectAccessGuard implements ProjectAccessGuard {
    private final ProjectMemberRepository members;

    public DatabaseProjectAccessGuard(ProjectMemberRepository members) {
        this.members = members;
    }

    @Override
    public ProjectRole requireMember(UUID projectId, UUID userId) {
        return members.findRole(projectId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PROJECT_NOT_FOUND));
    }

    @Override
    public void requireAdmin(UUID projectId, UUID userId) {
        if (!requireMember(projectId, userId).isAdminOrOwner()) {
            throw new BusinessException(ErrorCode.PROJECT_ADMIN_REQUIRED);
        }
    }

    @Override
    public void requireOwner(UUID projectId, UUID userId) {
        if (requireMember(projectId, userId) != ProjectRole.OWNER) {
            throw new BusinessException(ErrorCode.PROJECT_OWNER_REQUIRED);
        }
    }
}
