package com.shitulelv.aicollab.project.infrastructure.mapper;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 审计日志持久化查询行。仅用于 Mapper 层，不暴露给 API。
 */
public record AuditLogRow(
        UUID id,
        UUID userId,
        String userDisplayName,
        String action,
        String entityType,
        UUID entityId,
        String detailJson,
        UUID requestId,
        OffsetDateTime createdAt) {
}
