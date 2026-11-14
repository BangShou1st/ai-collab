package com.shitulelv.aicollab.agent.infrastructure.mcp;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * MCP Provider 人工准入快照。只有 {@link #active()} 为 true 的连接才能进入 Agent 目录。
 */
public record McpProviderAdmission(
        UUID connectionId,
        McpProviderType providerType,
        String providerOwner,
        String dataScope,
        String retentionPolicy,
        String termsUrl,
        String leastPrivilegeScope,
        boolean readOnlyEndpoint,
        McpAdmissionStatus status,
        UUID reviewedBy,
        OffsetDateTime reviewedAt,
        UUID revokedBy,
        OffsetDateTime revokedAt,
        int version,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    public boolean active() {
        return status == McpAdmissionStatus.APPROVED
                && providerType != null
                && nonBlank(providerOwner)
                && nonBlank(dataScope)
                && nonBlank(retentionPolicy)
                && nonBlank(termsUrl)
                && nonBlank(leastPrivilegeScope)
                && readOnlyEndpoint
                && reviewedBy != null
                && reviewedAt != null
                && revokedAt == null;
    }

    public boolean metadataComplete() {
        return providerType != null
                && nonBlank(providerOwner)
                && nonBlank(dataScope)
                && nonBlank(retentionPolicy)
                && nonBlank(termsUrl)
                && nonBlank(leastPrivilegeScope)
                && readOnlyEndpoint;
    }

    public boolean activeForReview() {
        return (status == McpAdmissionStatus.DRAFT || status == McpAdmissionStatus.REVOKED)
                && metadataComplete();
    }

    private static boolean nonBlank(String value) {
        return value != null && !value.isBlank();
    }
}
