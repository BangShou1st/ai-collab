package com.shitulelv.aicollab.knowledge.infrastructure.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

@TableName("knowledge_eval_run")
public class KnowledgeEvalRunEntity {
    @TableId private UUID id;
    private UUID projectId;
    private UUID triggeredBy;
    private String status;
    private int totalQuestions;
    private BigDecimal recallAt3;
    private BigDecimal recallAt5;
    private BigDecimal mrr;
    private BigDecimal avgSimilarity;
    private OffsetDateTime createdAt;
    private OffsetDateTime completedAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getProjectId() { return projectId; }
    public void setProjectId(UUID projectId) { this.projectId = projectId; }
    public UUID getTriggeredBy() { return triggeredBy; }
    public void setTriggeredBy(UUID triggeredBy) { this.triggeredBy = triggeredBy; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public int getTotalQuestions() { return totalQuestions; }
    public void setTotalQuestions(int totalQuestions) { this.totalQuestions = totalQuestions; }
    public BigDecimal getRecallAt3() { return recallAt3; }
    public void setRecallAt3(BigDecimal recallAt3) { this.recallAt3 = recallAt3; }
    public BigDecimal getRecallAt5() { return recallAt5; }
    public void setRecallAt5(BigDecimal recallAt5) { this.recallAt5 = recallAt5; }
    public BigDecimal getMrr() { return mrr; }
    public void setMrr(BigDecimal mrr) { this.mrr = mrr; }
    public BigDecimal getAvgSimilarity() { return avgSimilarity; }
    public void setAvgSimilarity(BigDecimal avgSimilarity) { this.avgSimilarity = avgSimilarity; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public OffsetDateTime getCompletedAt() { return completedAt; }
    public void setCompletedAt(OffsetDateTime completedAt) { this.completedAt = completedAt; }
}
