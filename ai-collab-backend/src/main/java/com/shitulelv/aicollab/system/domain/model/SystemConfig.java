package com.shitulelv.aicollab.system.domain.model;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 系统级动态配置聚合根。
 *
 * <p>承载系统管理员在网页端动态管理的全局运行参数，包含功能开关、Worker 调度、
 * 系统级 AI/RAG 策略及维护模式。</p>
 */
@com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
public record SystemConfig(
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
    public static final UUID DEFAULT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    public static final int MIN_CONCURRENCY = 1;
    public static final int MAX_CONCURRENCY = 4;
    public static final long MIN_WORKER_DELAY = 200;
    public static final long MAX_WORKER_DELAY = 60000;
    public static final long MIN_SCHEDULE_DELAY = 1000;
    public static final long MAX_SCHEDULE_DELAY = 300000;
    public static final double MIN_RAG_THRESHOLD = 0.0;
    public static final double MAX_RAG_THRESHOLD = 1.0;
    public static final int MIN_RAG_SOURCES = 1;
    public static final int MAX_RAG_SOURCES = 50;

    public static SystemConfig createDefault() {
        OffsetDateTime now = OffsetDateTime.now();
        return new SystemConfig(
                DEFAULT_ID,
                false,
                false,
                2,
                1000L,
                30000L,
                false,
                "",
                true,
                "",
                "",
                0.55,
                12,
                1L,
                null,
                now,
                now
        );
    }
}
