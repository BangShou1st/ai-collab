package com.shitulelv.aicollab.system.domain.model;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 系统配置变更审计领域模型。
 */
public record SystemConfigAudit(
        UUID id,
        long configVersion,
        String action,
        UUID changedBy,
        String changedByName,
        String changeSummary,
        String configSnapshot,
        String appliedStatus,
        String errorMessage,
        OffsetDateTime createdAt
) {}
