package com.shitulelv.aicollab.system.application.view;

import com.shitulelv.aicollab.system.domain.model.SystemConfig;

import java.time.OffsetDateTime;
import java.util.UUID;

public record SystemConfigView(
        UUID id,
        boolean agentEnabled,
        boolean chatEnabled,
        int workerConcurrency,
        long workerDelayMs,
        long scheduleDelayMs,
        boolean systemMaintenanceMode,
        String systemMaintenanceMessage,
        boolean publicRegistrationEnabled,
        String defaultChatModel,
        String defaultAgentModel,
        double ragRetrievalThreshold,
        int ragMaxSources,
        long version,
        UUID updatedBy,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
    public static SystemConfigView from(SystemConfig config) {
        return new SystemConfigView(
                config.id(),
                config.agentEnabled(),
                config.chatEnabled(),
                config.workerConcurrency(),
                config.workerDelayMs(),
                config.scheduleDelayMs(),
                config.systemMaintenanceMode(),
                config.systemMaintenanceMessage(),
                config.publicRegistrationEnabled(),
                config.defaultChatModel(),
                config.defaultAgentModel(),
                config.ragRetrievalThreshold(),
                config.ragMaxSources(),
                config.version(),
                config.updatedBy(),
                config.createdAt(),
                config.updatedAt()
        );
    }
}
