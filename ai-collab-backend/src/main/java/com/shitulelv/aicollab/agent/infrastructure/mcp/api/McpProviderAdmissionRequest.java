package com.shitulelv.aicollab.agent.infrastructure.mcp.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record McpProviderAdmissionRequest(
        @NotBlank @Size(max = 200) String providerOwner,
        @NotBlank @Size(max = 2000) String dataScope,
        @NotBlank @Size(max = 1000) String retentionPolicy,
        @NotBlank @Size(max = 1000)
        @Pattern(regexp = "https://.+|urn:ai-collab:local-fixture")
        String termsUrl,
        @NotBlank @Size(max = 1000) String leastPrivilegeScope,
        boolean readOnlyEndpoint,
        int version) {
}
