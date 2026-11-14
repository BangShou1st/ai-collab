package com.shitulelv.aicollab.infrastructure.ai.capability;

import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.infrastructure.ai.capability.api.CapabilityAssignmentRequest;
import com.shitulelv.aicollab.infrastructure.ai.capability.api.CapabilityAssignmentView;
import com.shitulelv.aicollab.project.application.service.AuditService;
import com.shitulelv.aicollab.project.domain.policy.ProjectAccessGuard;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * RERANK 用途绑定。capability/purpose 在服务端固定为 RERANK/KNOWLEDGE_RERANK，
 * 客户端不能把 Rerank 绑定到 KNOWLEDGE_CHAT 或其它用途。
 */
@Service
public class CapabilityAssignmentService {

    private final CapabilityAssignmentRepository assignments;
    private final CapabilityProfileRepository profiles;
    private final ProjectAccessGuard access;
    private final AuditService audit;

    public CapabilityAssignmentService(CapabilityAssignmentRepository assignments,
            CapabilityProfileRepository profiles, ProjectAccessGuard access, AuditService audit) {
        this.assignments = assignments;
        this.profiles = profiles;
        this.access = access;
        this.audit = audit;
    }

    @Transactional
    public CapabilityAssignmentView upsertGlobal(UUID adminId, CapabilityAssignmentRequest request) {
        CapabilityProfile profile = requirePublishedProfile(request.profileId());
        CapabilityAssignment existing = assignments.findGlobal(CapabilityType.RERANK,
                CapabilityPurpose.KNOWLEDGE_RERANK).orElse(null);
        CapabilityAssignment saved;
        String action;
        if (existing == null) {
            saved = assignments.create(adminId, CapabilityType.RERANK,
                    CapabilityPurpose.KNOWLEDGE_RERANK, CapabilityAssignmentScope.GLOBAL, null,
                    profile.id(), request.enabledOrDefault());
            action = "AI_CAPABILITY_ASSIGNMENT_CREATED";
        } else {
            if (!assignments.update(existing.id(), adminId, profile.id(), request.enabledOrDefault())) {
                throw new BusinessException(ErrorCode.CAPABILITY_PROFILE_CONFLICT,
                        "用途绑定已被其它请求修改，请刷新后重试");
            }
            saved = assignments.findById(existing.id()).orElseThrow();
            action = "AI_CAPABILITY_ASSIGNMENT_UPDATED";
        }
        audit(null, adminId, action, saved, profile, request.reason());
        return view(saved);
    }

    @Transactional
    public CapabilityAssignmentView upsertProject(UUID projectId, UUID userId,
            CapabilityAssignmentRequest request) {
        access.requireAdmin(projectId, userId);
        CapabilityProfile profile = requirePublishedProfile(request.profileId());
        CapabilityAssignment existing = assignments.findProject(projectId, CapabilityType.RERANK,
                CapabilityPurpose.KNOWLEDGE_RERANK).orElse(null);
        CapabilityAssignment saved;
        String action;
        if (existing == null) {
            saved = assignments.create(userId, CapabilityType.RERANK,
                    CapabilityPurpose.KNOWLEDGE_RERANK, CapabilityAssignmentScope.PROJECT,
                    projectId, profile.id(), request.enabledOrDefault());
            action = "AI_CAPABILITY_ASSIGNMENT_CREATED";
        } else {
            if (!assignments.update(existing.id(), userId, profile.id(), request.enabledOrDefault())) {
                throw new BusinessException(ErrorCode.CAPABILITY_PROFILE_CONFLICT,
                        "用途绑定已被其它请求修改，请刷新后重试");
            }
            saved = assignments.findById(existing.id()).orElseThrow();
            action = "AI_CAPABILITY_ASSIGNMENT_UPDATED";
        }
        audit(projectId, userId, action, saved, profile, request.reason());
        return view(saved);
    }

    public List<CapabilityAssignmentView> listAll() {
        return assignments.findAll().stream().map(this::view).toList();
    }

    public List<CapabilityAssignmentView> listProject(UUID projectId, UUID userId) {
        access.requireAdmin(projectId, userId);
        return assignments.findByProject(projectId).stream().map(this::view).toList();
    }

    @Transactional
    public void deleteGlobal(UUID adminId, UUID assignmentId) {
        CapabilityAssignment assignment = assignments.findById(assignmentId)
                .filter(found -> found.scope() == CapabilityAssignmentScope.GLOBAL)
                .orElseThrow(() -> new BusinessException(ErrorCode.CAPABILITY_ASSIGNMENT_NOT_FOUND));
        delete(null, adminId, assignment);
    }

    @Transactional
    public void deleteProject(UUID projectId, UUID userId, UUID assignmentId) {
        access.requireAdmin(projectId, userId);
        CapabilityAssignment assignment = assignments.findById(assignmentId)
                .filter(found -> found.scope() == CapabilityAssignmentScope.PROJECT
                        && projectId.equals(found.projectId()))
                .orElseThrow(() -> new BusinessException(ErrorCode.CAPABILITY_ASSIGNMENT_NOT_FOUND));
        if (!assignments.delete(assignment.id())) {
            throw new BusinessException(ErrorCode.CAPABILITY_ASSIGNMENT_NOT_FOUND);
        }
        audit(projectId, userId, "AI_CAPABILITY_ASSIGNMENT_DELETED", assignment,
                requireProfile(assignment.modelCapabilityProfileId()), null);
    }

    private void delete(UUID projectId, UUID actorId, UUID assignmentId) {
        CapabilityAssignment assignment = assignments.findById(assignmentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CAPABILITY_ASSIGNMENT_NOT_FOUND));
        delete(projectId, actorId, assignment);
    }

    private void delete(UUID projectId, UUID actorId, CapabilityAssignment assignment) {
        if (!assignments.delete(assignment.id())) {
            throw new BusinessException(ErrorCode.CAPABILITY_ASSIGNMENT_NOT_FOUND);
        }
        audit(projectId, actorId, "AI_CAPABILITY_ASSIGNMENT_DELETED", assignment,
                requireProfile(assignment.modelCapabilityProfileId()), null);
    }

    private CapabilityProfile requirePublishedProfile(UUID profileId) {
        CapabilityProfile profile = requireProfile(profileId);
        if (profile.capability() != CapabilityType.RERANK) {
            throw new BusinessException(ErrorCode.CAPABILITY_ASSIGNMENT_INVALID,
                    "当前只允许绑定 RERANK 能力档案");
        }
        if (!profile.isPublished()) {
            throw new BusinessException(ErrorCode.CAPABILITY_ASSIGNMENT_INVALID,
                    "用途绑定只能指向 PUBLISHED 能力档案");
        }
        return profile;
    }

    private CapabilityProfile requireProfile(UUID profileId) {
        return profiles.findById(profileId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CAPABILITY_PROFILE_NOT_FOUND));
    }

    private CapabilityAssignmentView view(CapabilityAssignment assignment) {
        CapabilityProfile profile = profiles.findById(assignment.modelCapabilityProfileId()).orElse(null);
        return CapabilityAssignmentView.from(assignment,
                profile == null ? null : profile.status().name(),
                profile == null ? 0 : profile.version());
    }

    private void audit(UUID projectId, UUID actorId, String action, CapabilityAssignment assignment,
            CapabilityProfile profile, String reason) {
        if (audit == null) {
            return;
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("assignmentId", assignment.id().toString());
        detail.put("capability", assignment.capability().name());
        detail.put("purpose", assignment.purpose());
        detail.put("scope", assignment.scope().name());
        if (assignment.projectId() != null) {
            detail.put("projectId", assignment.projectId().toString());
        }
        detail.put("profileId", assignment.modelCapabilityProfileId().toString());
        detail.put("profileVersion", profile.version());
        detail.put("enabled", assignment.enabled());
        if (reason != null && !reason.isBlank()) {
            detail.put("reason", reason.strip());
        }
        audit.write(projectId, actorId, action, "AI_CAPABILITY_ASSIGNMENT", assignment.id(), detail);
    }
}
