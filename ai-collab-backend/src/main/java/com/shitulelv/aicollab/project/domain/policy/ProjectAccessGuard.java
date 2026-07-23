package com.shitulelv.aicollab.project.domain.policy;

import com.shitulelv.aicollab.project.domain.model.ProjectRole;

import java.util.UUID;

public interface ProjectAccessGuard {
    ProjectRole requireMember(UUID projectId, UUID userId);
    void requireAdmin(UUID projectId, UUID userId);
    void requireOwner(UUID projectId, UUID userId);
}
