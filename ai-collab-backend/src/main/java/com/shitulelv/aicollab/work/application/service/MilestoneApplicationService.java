package com.shitulelv.aicollab.work.application.service;

import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.project.application.service.AuditService;
import com.shitulelv.aicollab.project.domain.model.ProjectRole;
import com.shitulelv.aicollab.project.domain.policy.ProjectAccessGuard;
import com.shitulelv.aicollab.work.api.dto.CreateMilestoneRequest;
import com.shitulelv.aicollab.work.api.dto.UpdateMilestoneRequest;
import com.shitulelv.aicollab.work.application.view.MilestoneView;
import com.shitulelv.aicollab.work.domain.model.MilestoneStatus;
import com.shitulelv.aicollab.work.domain.policy.WorkPermissionPolicy;
import com.shitulelv.aicollab.work.infrastructure.entity.MilestoneEntity;
import com.shitulelv.aicollab.work.infrastructure.repository.MilestoneRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class MilestoneApplicationService {
    private final ProjectAccessGuard access;
    private final WorkPermissionPolicy permissions;
    private final MilestoneRepository milestones;
    private final AuditService audit;

    public MilestoneApplicationService(
            ProjectAccessGuard access, WorkPermissionPolicy permissions,
            MilestoneRepository milestones, AuditService audit) {
        this.access = access;
        this.permissions = permissions;
        this.milestones = milestones;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<MilestoneView> list(UUID projectId, UUID userId) {
        access.requireMember(projectId, userId);
        return milestones.list(projectId).stream().map(MilestoneView::from).toList();
    }

    @Transactional
    public MilestoneView create(UUID projectId, CreateMilestoneRequest request, UUID userId) {
        ProjectRole role = access.requireMember(projectId, userId);
        permissions.requireAdmin(role);
        MilestoneEntity entity = new MilestoneEntity();
        entity.setId(UUID.randomUUID());
        entity.setProjectId(projectId);
        entity.setName(request.name().trim());
        entity.setDescription(request.description() == null ? "" : request.description());
        entity.setTargetDate(request.targetDate());
        entity.setStatus(request.status() == null ? MilestoneStatus.PLANNED : request.status());
        entity.setSortOrder(request.sortOrder() == null ? 0 : request.sortOrder());
        entity.setCreatedBy(userId);
        entity.setVersion(0);
        milestones.create(entity);
        audit.write(projectId, userId, "MILESTONE_CREATED", "MILESTONE", entity.getId());
        return MilestoneView.from(milestones.find(projectId, entity.getId()).orElseThrow());
    }

    @Transactional
    public MilestoneView update(
            UUID projectId, UUID milestoneId, UpdateMilestoneRequest request, UUID userId) {
        permissions.requireAdmin(access.requireMember(projectId, userId));
        MilestoneEntity entity = milestones.find(projectId, milestoneId)
                .orElseThrow(() -> new BusinessException(ErrorCode.MILESTONE_NOT_FOUND));
        entity.setName(request.name().trim());
        entity.setDescription(request.description() == null ? "" : request.description());
        entity.setTargetDate(request.targetDate());
        entity.setStatus(request.status());
        entity.setSortOrder(request.sortOrder());
        entity.setVersion(request.version());
        if (!milestones.update(projectId, entity)) {
            throw new BusinessException(ErrorCode.VERSION_CONFLICT);
        }
        audit.write(projectId, userId, "MILESTONE_UPDATED", "MILESTONE", milestoneId);
        return MilestoneView.from(milestones.find(projectId, milestoneId).orElseThrow());
    }

    @Transactional
    public void delete(UUID projectId, UUID milestoneId, UUID userId) {
        permissions.requireAdmin(access.requireMember(projectId, userId));
        if (!milestones.delete(projectId, milestoneId)) {
            throw new BusinessException(ErrorCode.MILESTONE_NOT_FOUND);
        }
        audit.write(projectId, userId, "MILESTONE_DELETED", "MILESTONE", milestoneId);
    }
}
