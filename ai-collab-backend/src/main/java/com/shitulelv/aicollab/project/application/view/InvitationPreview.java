package com.shitulelv.aicollab.project.application.view;

import com.shitulelv.aicollab.project.domain.model.ProjectRole;

import java.time.OffsetDateTime;
import java.util.UUID;

public record InvitationPreview(
        UUID projectId,
        String projectName,
        ProjectRole role,
        String invitedEmail,
        OffsetDateTime expiresAt) {
}
