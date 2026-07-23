package com.shitulelv.aicollab.project.application.view;

import com.shitulelv.aicollab.project.domain.model.ProjectRole;

import java.time.OffsetDateTime;

public record InvitationPreview(
        String projectName,
        ProjectRole role,
        String invitedEmail,
        OffsetDateTime expiresAt) {
}
