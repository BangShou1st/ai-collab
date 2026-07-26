package com.shitulelv.aicollab.planning.infrastructure;

import java.time.OffsetDateTime;
import java.util.UUID;

public record TaskPlanVersionRecord(
        UUID id, UUID planId, int versionNo, String sourceType, UUID basedOnVersionId,
        long generationSeq, String summary, String assumptionsJson, String risksJson,
        String milestonesJson, String tasksJson, String sourcesJson,
        String validationResultJson, UUID createdBy, OffsetDateTime createdAt) {}
