package com.shitulelv.aicollab.project.application.view;

import com.shitulelv.aicollab.project.domain.model.ProjectRole;

import java.time.OffsetDateTime;
import java.util.UUID;

public record InvitationView(UUID id, String code, UUID projectId, ProjectRole role, OffsetDateTime expiresAt) {
}
