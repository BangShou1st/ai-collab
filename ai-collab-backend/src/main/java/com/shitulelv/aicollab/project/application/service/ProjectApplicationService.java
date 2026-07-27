package com.shitulelv.aicollab.project.application.service;

import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.project.api.dto.CreateProjectRequest;
import com.shitulelv.aicollab.project.api.dto.UpdateProjectRequest;
import com.shitulelv.aicollab.project.application.view.ProjectView;
import com.shitulelv.aicollab.project.domain.model.ProjectRole;
import com.shitulelv.aicollab.project.domain.model.ProjectStatus;
import com.shitulelv.aicollab.project.domain.policy.ProjectAccessGuard;
import com.shitulelv.aicollab.project.infrastructure.entity.ProjectEntity;
import com.shitulelv.aicollab.project.infrastructure.repository.ProjectMemberRepository;
import com.shitulelv.aicollab.project.infrastructure.repository.ProjectRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class ProjectApplicationService {
    private final ProjectRepository projects;
    private final ProjectMemberRepository members;
    private final ProjectAccessGuard accessGuard;
    private final AuditService audit;

    public ProjectApplicationService(
            ProjectRepository projects,
            ProjectMemberRepository members,
            ProjectAccessGuard accessGuard,
            AuditService audit) {
        this.projects = projects;
        this.members = members;
        this.accessGuard = accessGuard;
        this.audit = audit;
    }

    @Transactional
    public ProjectView create(CreateProjectRequest request, UUID userId) {
        validateDates(request.startDate(), request.dueDate());
        UUID projectId = UUID.randomUUID();
        ProjectEntity entity = new ProjectEntity();
        entity.setId(projectId);
        entity.setName(request.name().trim());
        entity.setDescription(request.description() == null ? "" : request.description());
        entity.setOwnerId(userId);
        entity.setStartDate(request.startDate());
        entity.setDueDate(request.dueDate());
        entity.setStatus(ProjectStatus.ACTIVE);
        entity.setCreatedBy(userId);
        entity.setVersion(0);
        projects.create(entity);
        members.create(projectId, userId, ProjectRole.OWNER, null);
        audit.write(projectId, userId, "PROJECT_CREATED", "PROJECT", projectId);
        return get(projectId, userId);
    }

    @Transactional(readOnly = true)
    public List<ProjectView> list(UUID userId) {
        return projects.listForUser(userId);
    }

    @Transactional(readOnly = true)
    public ProjectView get(UUID projectId, UUID userId) {
        return projects.findForMember(projectId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PROJECT_NOT_FOUND));
    }

    @Transactional
    public ProjectView update(UUID projectId, UpdateProjectRequest request, UUID userId) {
        accessGuard.requireOwner(projectId, userId);
        validateDates(request.startDate(), request.dueDate());
        ProjectStatus status = request.status() == null ? get(projectId, userId).status() : request.status();
        if (!projects.updateWithVersion(
                projectId,
                request.name().trim(),
                request.description() == null ? "" : request.description(),
                request.startDate(),
                request.dueDate(),
                status,
                request.version())) {
            throw new BusinessException(ErrorCode.VERSION_CONFLICT);
        }
        audit.write(projectId, userId, "PROJECT_UPDATED", "PROJECT", projectId);
        return get(projectId, userId);
    }

    @Transactional
    public void delete(UUID projectId, UUID userId) {
        accessGuard.requireOwner(projectId, userId);
        if (!projects.lock(projectId)) {
            throw new BusinessException(ErrorCode.PROJECT_NOT_FOUND);
        }
        if (projects.countDocuments(projectId) > 0) {
            throw new BusinessException(ErrorCode.PROJECT_DOCUMENTS_EXIST);
        }
        // H8: Check for confirmed plans before delete — stable 409 instead of database trigger error
        if (projects.countConfirmedPlans(projectId) > 0) {
            throw new BusinessException(ErrorCode.PROJECT_CONFIRMED_PLANS_EXIST);
        }
        // project_id 外键使用 ON DELETE CASCADE；删除审计以 null project_id 保存，entity_id 仍标识被删项目。
        audit.write(null, userId, "PROJECT_DELETED", "PROJECT", projectId);
        if (!projects.delete(projectId)) {
            throw new BusinessException(ErrorCode.PROJECT_NOT_FOUND);
        }
    }

    private static void validateDates(java.time.LocalDate startDate, java.time.LocalDate dueDate) {
        if (startDate != null && dueDate != null && startDate.isAfter(dueDate)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "startDate 不能晚于 dueDate");
        }
    }
}
