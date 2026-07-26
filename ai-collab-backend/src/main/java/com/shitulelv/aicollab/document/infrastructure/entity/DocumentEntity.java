package com.shitulelv.aicollab.document.infrastructure.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.shitulelv.aicollab.document.domain.model.DocumentStatus;

import java.time.OffsetDateTime;
import java.util.UUID;

@TableName("project_document")
public class DocumentEntity {
    @TableId private UUID id;
    private UUID projectId;
    private String displayName;
    private String originalFilename;
    private String mimeType;
    private Long sizeBytes;
    private String objectKey;
    private DocumentStatus status;
    private String parserType;
    private Integer chunkCount;
    private String embeddingProvider;
    private String embeddingModel;
    private Integer embeddingDimension;
    private String errorMessage;
    private UUID processingToken;
    private OffsetDateTime processingHeartbeatAt;
    private UUID uploadedBy;
    private OffsetDateTime indexedAt;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
    @TableField(exist = false) private String uploadedByDisplayName;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getProjectId() { return projectId; }
    public void setProjectId(UUID projectId) { this.projectId = projectId; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public String getOriginalFilename() { return originalFilename; }
    public void setOriginalFilename(String originalFilename) { this.originalFilename = originalFilename; }
    public String getMimeType() { return mimeType; }
    public void setMimeType(String mimeType) { this.mimeType = mimeType; }
    public Long getSizeBytes() { return sizeBytes; }
    public void setSizeBytes(Long sizeBytes) { this.sizeBytes = sizeBytes; }
    public String getObjectKey() { return objectKey; }
    public void setObjectKey(String objectKey) { this.objectKey = objectKey; }
    public DocumentStatus getStatus() { return status; }
    public void setStatus(DocumentStatus status) { this.status = status; }
    public String getParserType() { return parserType; }
    public void setParserType(String parserType) { this.parserType = parserType; }
    public Integer getChunkCount() { return chunkCount; }
    public void setChunkCount(Integer chunkCount) { this.chunkCount = chunkCount; }
    public String getEmbeddingProvider() { return embeddingProvider; }
    public void setEmbeddingProvider(String embeddingProvider) { this.embeddingProvider = embeddingProvider; }
    public String getEmbeddingModel() { return embeddingModel; }
    public void setEmbeddingModel(String embeddingModel) { this.embeddingModel = embeddingModel; }
    public Integer getEmbeddingDimension() { return embeddingDimension; }
    public void setEmbeddingDimension(Integer embeddingDimension) { this.embeddingDimension = embeddingDimension; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public UUID getProcessingToken() { return processingToken; }
    public void setProcessingToken(UUID processingToken) { this.processingToken = processingToken; }
    public OffsetDateTime getProcessingHeartbeatAt() { return processingHeartbeatAt; }
    public void setProcessingHeartbeatAt(OffsetDateTime value) { this.processingHeartbeatAt = value; }
    public UUID getUploadedBy() { return uploadedBy; }
    public void setUploadedBy(UUID uploadedBy) { this.uploadedBy = uploadedBy; }
    public OffsetDateTime getIndexedAt() { return indexedAt; }
    public void setIndexedAt(OffsetDateTime indexedAt) { this.indexedAt = indexedAt; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
    public String getUploadedByDisplayName() { return uploadedByDisplayName; }
    public void setUploadedByDisplayName(String value) { this.uploadedByDisplayName = value; }
}
