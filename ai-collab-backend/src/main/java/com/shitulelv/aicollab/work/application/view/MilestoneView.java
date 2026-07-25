package com.shitulelv.aicollab.work.application.view;

import com.shitulelv.aicollab.work.domain.model.MilestoneStatus;
import com.shitulelv.aicollab.work.infrastructure.entity.MilestoneEntity;

import java.time.LocalDate;
import java.util.UUID;

public record MilestoneView(
        UUID id, UUID projectId, String name, String description, LocalDate targetDate,
        MilestoneStatus status, int sortOrder, int version) {
    public static MilestoneView from(MilestoneEntity entity) {
        return new MilestoneView(entity.getId(), entity.getProjectId(), entity.getName(), entity.getDescription(),
                entity.getTargetDate(), entity.getStatus(), entity.getSortOrder(), entity.getVersion());
    }
}
