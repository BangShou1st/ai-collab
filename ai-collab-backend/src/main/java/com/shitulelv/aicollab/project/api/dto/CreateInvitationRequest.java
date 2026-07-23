package com.shitulelv.aicollab.project.api.dto;

import com.shitulelv.aicollab.project.domain.model.ProjectRole;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateInvitationRequest(
        @NotNull ProjectRole role,
        @Email @Size(max = 120) String invitedEmail,
        @NotNull @Min(1) @Max(168) Integer expiresInHours) {
}
