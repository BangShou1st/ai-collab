package com.shitulelv.aicollab.knowledge.infrastructure.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.OffsetDateTime;
import java.util.UUID;

@TableName("knowledge_feedback")
public class KnowledgeFeedbackEntity {
    @TableId private UUID id;
    private UUID messageId;
    private UUID userId;
    private boolean helpful;
    private OffsetDateTime createdAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getMessageId() { return messageId; }
    public void setMessageId(UUID messageId) { this.messageId = messageId; }
    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }
    public boolean isHelpful() { return helpful; }
    public void setHelpful(boolean helpful) { this.helpful = helpful; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
