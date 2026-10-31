package com.shitulelv.aicollab.connector.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record CreateSourceRequest(
        @NotNull UUID connectionId,
        @NotBlank @Size(max = 120) String folderToken,
        @NotBlank @Size(max = 200) String name,
        @NotNull boolean authorizationConfirmed,
        UUID userAuthorizationId) {
}
