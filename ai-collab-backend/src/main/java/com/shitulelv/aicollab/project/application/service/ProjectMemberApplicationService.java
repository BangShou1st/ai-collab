package com.shitulelv.aicollab.project.application.service;

import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.project.application.view.MemberView;
import com.shitulelv.aicollab.project.domain.model.ProjectRole;
import com.shitulelv.aicollab.project.domain.policy.ProjectAccessGuard;
import com.shitulelv.aicollab.project.infrastructure.repository.ProjectMemberRepository;
import com.shitulelv.aicollab.project.infrastructure.repository.ProjectRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class ProjectMemberApplicationService {
    private final ProjectMemberRepository members;
    private final ProjectRepository projects;
    private final ProjectAccessGuard accessGuard;
    private final AuditService audit;

    public ProjectMemberApplicationService(
            ProjectMemberRepository members,
            ProjectRepository projects,
            ProjectAccessGuard accessGuard,
            AuditService audit) {
        this.members = members;
        this.projects = projects;
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
        audit.write(projectId, operatorId, "PROJECT_MEMBER_ROLE_CHANGED", "PROJECT_MEMBER", userId,
                Map.of("previousRole", current.name(), "newRole", role.name()));
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

    @Transactional
    public void leave(UUID projectId, UUID userId) {
        ProjectRole current = members.findRole(projectId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.MEMBER_NOT_FOUND));
        if (current == ProjectRole.OWNER) {
            throw new BusinessException(ErrorCode.PROJECT_OWNER_CANNOT_LEAVE);
        }
        if (!members.removeNonOwner(projectId, userId)) {
            throw new BusinessException(ErrorCode.MEMBER_NOT_FOUND);
        }
        audit.write(projectId, userId, "PROJECT_MEMBER_LEFT", "PROJECT_MEMBER", userId);
    }

    @Transactional
    public void transferOwnership(UUID projectId, UUID newOwnerId, UUID operatorId) {
        accessGuard.requireMember(projectId, operatorId);
        if (!projects.lock(projectId)) {
            throw new BusinessException(ErrorCode.PROJECT_NOT_FOUND);
        }
        UUID currentOwnerId = members.lockOwner(projectId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PROJECT_OWNERSHIP_CONFLICT));
        if (!currentOwnerId.equals(operatorId)) {
            throw new BusinessException(ErrorCode.PROJECT_OWNER_REQUIRED);
        }
        if (operatorId.equals(newOwnerId)) {
            throw new BusinessException(ErrorCode.PROJECT_OWNERSHIP_SELF_TRANSFER);
        }
        if (members.findRole(projectId, newOwnerId).isEmpty()) {
            throw new BusinessException(ErrorCode.PROJECT_OWNERSHIP_TARGET_NOT_MEMBER);
        }
        if (!projects.transferOwnership(projectId, currentOwnerId, newOwnerId)) {
            throw new BusinessException(ErrorCode.PROJECT_OWNERSHIP_CONFLICT);
        }
        if (!members.demoteOwner(projectId, currentOwnerId)) {
            throw new BusinessException(ErrorCode.PROJECT_OWNERSHIP_CONFLICT);
        }
        if (!members.promoteToOwner(projectId, newOwnerId)) {
            throw new BusinessException(ErrorCode.PROJECT_OWNERSHIP_TARGET_NOT_MEMBER);
        }

        audit.write(projectId, operatorId, "PROJECT_OWNERSHIP_TRANSFERRED", "PROJECT", projectId,
                Map.of("previousOwnerId", currentOwnerId.toString(), "newOwnerId", newOwnerId.toString()));
    }
}
