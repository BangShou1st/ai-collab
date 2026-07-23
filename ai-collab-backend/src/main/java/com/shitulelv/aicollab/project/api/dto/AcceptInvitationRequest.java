package com.shitulelv.aicollab.project.api.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record AcceptInvitationRequest(
        @NotBlank @Pattern(regexp = "^[a-zA-Z0-9_]{3,40}$") String username,
        @NotBlank @Size(min = 8, max = 72) String password,
        @NotBlank @Size(max = 60) String displayName,
        @Email @Size(max = 120) String email) {
}
