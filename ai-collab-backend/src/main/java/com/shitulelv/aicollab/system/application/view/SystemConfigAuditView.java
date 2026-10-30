package com.shitulelv.aicollab.system.application.view;

import com.shitulelv.aicollab.system.domain.model.SystemConfigAudit;

import java.time.OffsetDateTime;
import java.util.UUID;

public record SystemConfigAuditView(
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
) {
    public static SystemConfigAuditView from(SystemConfigAudit audit) {
        return new SystemConfigAuditView(
                audit.id(),
                audit.configVersion(),
                audit.action(),
                audit.changedBy(),
                audit.changedByName(),
                audit.changeSummary(),
                audit.configSnapshot(),
                audit.appliedStatus(),
                audit.errorMessage(),
                audit.createdAt()
        );
    }
}
