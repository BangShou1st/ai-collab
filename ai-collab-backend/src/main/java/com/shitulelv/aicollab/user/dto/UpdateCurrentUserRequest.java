package com.shitulelv.aicollab.user.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpdateCurrentUserRequest(
        @NotBlank @Size(max = 60) String displayName,
        @Email @Size(max = 120) String email) {
}
