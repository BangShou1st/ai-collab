package com.shitulelv.aicollab.agent.application.view;

import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.UUID;

public record AgentScheduleView(
        UUID id, UUID projectId, UUID creatorId, UUID sessionId,
        String name, String goal, String frequency, String timeZone,
        LocalTime localTime, Integer weeklyDay, boolean enabled,
        OffsetDateTime nextFireAt, UUID lastRunId, String lastStatus,
        int version, OffsetDateTime createdAt, OffsetDateTime updatedAt) {
}
