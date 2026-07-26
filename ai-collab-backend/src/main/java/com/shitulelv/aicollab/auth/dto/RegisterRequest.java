package com.shitulelv.aicollab.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @NotBlank @Size(min = 3, max = 40)
        @Pattern(regexp = "[A-Za-z0-9_]+", message = "只允许字母、数字和下划线")
        String username,
        @NotBlank @Size(min = 8, max = 72) String password,
        @NotBlank @Size(max = 60) String displayName,
        @Email @Size(max = 120) String email) {
}
