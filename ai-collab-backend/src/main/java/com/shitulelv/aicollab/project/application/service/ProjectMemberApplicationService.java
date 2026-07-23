package com.shitulelv.aicollab.project.application.service;

import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.project.application.view.MemberView;
import com.shitulelv.aicollab.project.domain.model.ProjectRole;
import com.shitulelv.aicollab.project.domain.policy.ProjectAccessGuard;
import com.shitulelv.aicollab.project.infrastructure.repository.ProjectMemberRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class ProjectMemberApplicationService {
    private final ProjectMemberRepository members;
    private final ProjectAccessGuard accessGuard;
    private final AuditService audit;

    public ProjectMemberApplicationService(
            ProjectMemberRepository members,
            ProjectAccessGuard accessGuard,
            AuditService audit) {
        this.members = members;
        this.accessGuard = accessGuard;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<MemberView> list(UUID projectId, UUID operatorId) {
        accessGuard.requireMember(projectId, operatorId);
        return members.list(projectId);
    }

    @Transactional
    public MemberView changeRole(UUID projectId, UUID userId, ProjectRole role, UUID operatorId) {
        accessGuard.requireOwner(projectId, operatorId);
        if (role == ProjectRole.OWNER) {
            throw new BusinessException(ErrorCode.PROJECT_OWNER_CANNOT_BE_REMOVED);
        }
        ProjectRole current = members.findRole(projectId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.MEMBER_NOT_FOUND));
        if (current == ProjectRole.OWNER) {
            throw new BusinessException(ErrorCode.PROJECT_OWNER_CANNOT_BE_REMOVED);
        }
        if (!members.changeNonOwnerRole(projectId, userId, role)) {
            throw new BusinessException(ErrorCode.MEMBER_NOT_FOUND);
        }
        audit.write(projectId, operatorId, "PROJECT_MEMBER_ROLE_CHANGED", "PROJECT_MEMBER", userId);
        return members.find(projectId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.MEMBER_NOT_FOUND));
    }

    @Transactional
    public void remove(UUID projectId, UUID userId, UUID operatorId) {
        accessGuard.requireOwner(projectId, operatorId);
        ProjectRole current = members.findRole(projectId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.MEMBER_NOT_FOUND));
        if (current == ProjectRole.OWNER) {
            throw new BusinessException(ErrorCode.PROJECT_OWNER_CANNOT_BE_REMOVED);
        }
        if (!members.removeNonOwner(projectId, userId)) {
            throw new BusinessException(ErrorCode.MEMBER_NOT_FOUND);
        }
        audit.write(projectId, operatorId, "PROJECT_MEMBER_REMOVED", "PROJECT_MEMBER", userId);
    }
}
