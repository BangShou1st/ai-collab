package com.shitulelv.aicollab.work.application.view;

import com.shitulelv.aicollab.work.infrastructure.entity.TaskCommentEntity;

import java.time.OffsetDateTime;
import java.util.UUID;

public record TaskCommentView(
        UUID id, UUID taskId, UUID authorId, String authorDisplayName, String content,
        OffsetDateTime createdAt, OffsetDateTime updatedAt) {
    public static TaskCommentView from(TaskCommentEntity entity) {
        return new TaskCommentView(entity.getId(), entity.getTaskId(), entity.getAuthorId(),
                entity.getAuthorDisplayName(), entity.getContent(), entity.getCreatedAt(), entity.getUpdatedAt());
    }
}
