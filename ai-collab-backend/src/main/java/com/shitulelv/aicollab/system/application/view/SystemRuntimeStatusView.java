package com.shitulelv.aicollab.system.application.view;

import java.time.OffsetDateTime;

/**
 * 实时运行状态与诊断数据视图。
 */
public record SystemRuntimeStatusView(
        boolean agentEnabled,
        boolean chatEnabled,
        boolean agentRuntimeAllowed,
        int configuredWorkerConcurrency,
        int activeWorkerThreads,
        int queuedAgentRuns,
        int runningAgentRuns,
        int recentFailedAgentRuns,
        String lastExecutionError,
        boolean maintenanceMode,
        OffsetDateTime refreshedAt
) {}
