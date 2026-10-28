package com.shitulelv.aicollab.connector.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateConnectionRequest(
        @NotBlank @Size(max = 100) String name,
        @NotBlank @Size(max = 120) String appId,
        @NotBlank @Size(max = 200) String appSecret) {
}
