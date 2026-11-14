package com.shitulelv.aicollab.agent.infrastructure.mcp.api;

import com.shitulelv.aicollab.agent.infrastructure.mcp.McpAdmissionStatus;
import com.shitulelv.aicollab.agent.infrastructure.mcp.McpProviderAdmission;
import com.shitulelv.aicollab.agent.infrastructure.mcp.McpProviderType;

import java.time.OffsetDateTime;
import java.util.UUID;

public record McpProviderAdmissionView(
        UUID connectionId,
        McpProviderType providerType,
        String providerOwner,
        String dataScope,
        String retentionPolicy,
        String termsUrl,
        String leastPrivilegeScope,
        boolean readOnlyEndpoint,
        McpAdmissionStatus status,
        boolean metadataComplete,
        boolean approved,
        UUID reviewedBy,
        OffsetDateTime reviewedAt,
        UUID revokedBy,
        OffsetDateTime revokedAt,
        int version,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    public static McpProviderAdmissionView empty(UUID connectionId) {
        return new McpProviderAdmissionView(connectionId, null, null, null, null, null,
                null, false, McpAdmissionStatus.DRAFT, false, false, null, null,
                null, null, 0, null, null);
    }

    public static McpProviderAdmissionView from(McpProviderAdmission value) {
        return new McpProviderAdmissionView(
                value.connectionId(), value.providerType(), value.providerOwner(),
                value.dataScope(), value.retentionPolicy(), value.termsUrl(),
                value.leastPrivilegeScope(), value.readOnlyEndpoint(), value.status(),
                value.metadataComplete(), value.active(), value.reviewedBy(), value.reviewedAt(),
                value.revokedBy(), value.revokedAt(), value.version(),
                value.createdAt(), value.updatedAt());
    }
}
