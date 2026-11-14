package com.shitulelv.aicollab.infrastructure.ai.capability.api;

import java.util.List;
import java.util.UUID;

public record CapabilityTestResponse(
        UUID profileId,
        UUID providerId,
        String profileStatus,
        int profileVersion,
        String outcome,
        String reason,
        List<CapabilityTestScoreView> scores,
        Integer modelLatencyMs,
        long totalMs,
        long overheadMs,
        int candidateCount,
        int topK) {
}
