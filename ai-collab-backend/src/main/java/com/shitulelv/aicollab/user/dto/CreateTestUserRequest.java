package com.shitulelv.aicollab.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateTestUserRequest(
        @NotBlank @Size(max = 40) String username,
        @NotBlank @Size(max = 60) String displayName,
        @NotBlank @Size(min = 6, max = 100) String password) {
}
