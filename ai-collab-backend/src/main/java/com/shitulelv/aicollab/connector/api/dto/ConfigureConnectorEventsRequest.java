package com.shitulelv.aicollab.connector.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ConfigureConnectorEventsRequest(
        @NotBlank @Size(min=16,max=256) String encryptKey,
        @NotBlank @Size(min=16,max=256) String verificationToken) {
    @Override public String toString() { return "ConfigureConnectorEventsRequest[redacted]"; }
}
