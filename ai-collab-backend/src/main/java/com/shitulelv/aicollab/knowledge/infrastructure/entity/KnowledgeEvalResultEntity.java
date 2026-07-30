package com.shitulelv.aicollab.knowledge.infrastructure.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

@TableName("knowledge_eval_result")
public class KnowledgeEvalResultEntity {
    @TableId private UUID id;
    private UUID runId;
    private String question;
    private String expectedDocumentIds;
    private String retrievedDocumentIds;
    private BigDecimal recallAt3;
    private BigDecimal recallAt5;
    private BigDecimal mrr;
    private OffsetDateTime createdAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getRunId() { return runId; }
    public void setRunId(UUID runId) { this.runId = runId; }
    public String getQuestion() { return question; }
    public void setQuestion(String question) { this.question = question; }
    public String getExpectedDocumentIds() { return expectedDocumentIds; }
    public void setExpectedDocumentIds(String expectedDocumentIds) { this.expectedDocumentIds = expectedDocumentIds; }
    public String getRetrievedDocumentIds() { return retrievedDocumentIds; }
    public void setRetrievedDocumentIds(String retrievedDocumentIds) { this.retrievedDocumentIds = retrievedDocumentIds; }
    public BigDecimal getRecallAt3() { return recallAt3; }
    public void setRecallAt3(BigDecimal recallAt3) { this.recallAt3 = recallAt3; }
    public BigDecimal getRecallAt5() { return recallAt5; }
    public void setRecallAt5(BigDecimal recallAt5) { this.recallAt5 = recallAt5; }
    public BigDecimal getMrr() { return mrr; }
    public void setMrr(BigDecimal mrr) { this.mrr = mrr; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
