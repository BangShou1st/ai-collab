package com.shitulelv.aicollab.project.application.view;

import com.shitulelv.aicollab.project.domain.model.ProjectRole;

import java.util.UUID;

public record InvitationAcceptanceView(
        UUID projectId,
        String projectName,
        ProjectRole role,
        boolean alreadyMember) {
}
