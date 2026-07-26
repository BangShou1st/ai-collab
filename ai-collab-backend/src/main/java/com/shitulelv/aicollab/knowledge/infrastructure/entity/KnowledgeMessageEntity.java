package com.shitulelv.aicollab.knowledge.infrastructure.entity;

import java.time.OffsetDateTime;
import java.util.UUID;

public class KnowledgeMessageEntity {
    private UUID id;
    private UUID sessionId;
    private String role;
    private String content;
    private boolean insufficientEvidence;
    private String modelProvider;
    private String modelName;
    private Integer latencyMs;
    private Integer promptTokens;
    private Integer completionTokens;
    private OffsetDateTime createdAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getSessionId() { return sessionId; }
    public void setSessionId(UUID sessionId) { this.sessionId = sessionId; }
    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public boolean isInsufficientEvidence() { return insufficientEvidence; }
    public void setInsufficientEvidence(boolean insufficientEvidence) { this.insufficientEvidence = insufficientEvidence; }
    public String getModelProvider() { return modelProvider; }
    public void setModelProvider(String modelProvider) { this.modelProvider = modelProvider; }
    public String getModelName() { return modelName; }
    public void setModelName(String modelName) { this.modelName = modelName; }
    public Integer getLatencyMs() { return latencyMs; }
    public void setLatencyMs(Integer latencyMs) { this.latencyMs = latencyMs; }
    public Integer getPromptTokens() { return promptTokens; }
    public void setPromptTokens(Integer promptTokens) { this.promptTokens = promptTokens; }
    public Integer getCompletionTokens() { return completionTokens; }
    public void setCompletionTokens(Integer completionTokens) { this.completionTokens = completionTokens; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
