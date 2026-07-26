package com.shitulelv.aicollab.planning.infrastructure;

import com.shitulelv.aicollab.planning.domain.TaskPlanStatus;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

public record TaskPlanRecord(
        UUID id, UUID projectId, String title, String goal, String constraints,
        LocalDate planStartDate, LocalDate planDueDate, int maxTaskCount,
        String selectedDocumentIdsJson, TaskPlanStatus status, int latestVersionNo,
        UUID latestVersionId, long generationSeq, UUID activeAttemptId, UUID createdBy,
        String lastErrorCode, String lastErrorSummary, OffsetDateTime createdAt, OffsetDateTime updatedAt) {}
