package com.shitulelv.aicollab.project.application.view;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 审计日志 API 视图。
 * <p>
 * detail 为 Object（实际是 Map），Jackson 序列化时输出 JSON object 而非字符串。
 * summary 由应用层派生，不由 Mapper 直接映射。
 */
public record AuditLogItemView(
        UUID id,
        UUID userId,
        String userDisplayName,
        String action,
        String entityType,
        UUID entityId,
        Object detail,
        String summary,
        UUID requestId,
        OffsetDateTime createdAt) {
}
