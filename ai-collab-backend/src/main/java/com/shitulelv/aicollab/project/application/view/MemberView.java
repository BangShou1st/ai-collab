package com.shitulelv.aicollab.project.application.view;

import com.shitulelv.aicollab.project.domain.model.ProjectRole;

import java.time.OffsetDateTime;
import java.util.UUID;

public record MemberView(
        UUID userId,
        String username,
        String displayName,
        ProjectRole role,
        OffsetDateTime joinedAt) {
}
