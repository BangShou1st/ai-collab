package com.shitulelv.aicollab.agent.application.view;

import com.shitulelv.aicollab.agent.domain.model.AgentRunStatus;

import java.time.OffsetDateTime;
import java.util.UUID;

public record AgentRunView(
        UUID id,
        UUID sessionId,
        UUID projectId,
        UUID requesterId,
        UUID parentRunId,
        String role,
        int depth,
        String goal,
        AgentRunStatus status,
        int maxSteps,
        int maxToolCalls,
        int maxChildren,
        int maxInputTokens,
        int maxOutputTokens,
        int stepsUsed,
        int toolCallsUsed,
        int childrenUsed,
        int inputTokensUsed,
        int outputTokensUsed,
        boolean tokenUsageEstimated,
        boolean scheduled,
        boolean correctionAttempted,
        int retryCount,
        String errorCode,
        String planJson,
        String pageContextJson,
        String skillCode,
        int version,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {
}
