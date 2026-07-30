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

    /**
     * 转移项目所有权。
     *
     * 并发语义：
     * 1. 锁定当前 OWNER 的 project_member 行（SELECT ... FOR UPDATE）
     * 2. 验证当前用户是 OWNER
     * 3. 验证目标用户是项目成员
     * 4. 原子更新：project.owner_id + 旧 OWNER 降级 + 新 OWNER 升级
     *
     * @param projectId     项目 ID
     * @param newOwnerId    新所有者用户 ID
     * @param operatorId    当前操作者（必须是 OWNER）
     */
    @Transactional
    public void transferOwnership(UUID projectId, UUID newOwnerId, UUID operatorId) {
        // 验证操作者是 OWNER
        accessGuard.requireOwner(projectId, operatorId);

        // 目标用户不能是自己
        if (operatorId.equals(newOwnerId)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "不能将所有权转移给自己");
        }

        // 验证目标用户是项目成员
        ProjectRole newOwnerCurrentRole = members.findRole(projectId, newOwnerId)
                .orElseThrow(() -> new BusinessException(ErrorCode.MEMBER_NOT_FOUND));

        // 锁定当前 OWNER 行以确保并发安全
        if (!members.lockOwner(projectId, operatorId)) {
            throw new BusinessException(ErrorCode.MEMBER_NOT_FOUND);
        }

        // 执行所有权转移：更新 project.owner_id + 角色变更
        if (!projects.transferOwnership(projectId, newOwnerId)) {
            throw new BusinessException(ErrorCode.VERSION_CONFLICT);
        }

        // 旧 OWNER 降级为 MEMBER
        members.changeRoleDirect(projectId, operatorId, ProjectRole.MEMBER);

        // 新 OWNER 升级（如果当前不是 ADMIN，先升级为 ADMIN，再升级为 OWNER）
        if (newOwnerCurrentRole != ProjectRole.ADMIN) {
            members.changeRoleDirect(projectId, newOwnerId, ProjectRole.OWNER);
        } else {
            // 需要先删除当前成员记录，再插入 OWNER 记录
            // 但因为有唯一索引，需要使用原子操作
            members.promoteToOwner(projectId, newOwnerId);
        }

        audit.write(projectId, operatorId, "PROJECT_OWNERSHIP_TRANSFERRED", "PROJECT", projectId,
                Map.of("previousOwnerId", operatorId.toString(), "newOwnerId", newOwnerId.toString()));
    }
}
