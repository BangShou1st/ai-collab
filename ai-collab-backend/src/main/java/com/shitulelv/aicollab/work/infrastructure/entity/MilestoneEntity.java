package com.shitulelv.aicollab.work.infrastructure.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.shitulelv.aicollab.work.domain.model.MilestoneStatus;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

@TableName("milestone")
public class MilestoneEntity {
    @TableId
    private UUID id;
    private UUID projectId;
    private String name;
    private String description;
    private LocalDate targetDate;
    private MilestoneStatus status;
    private Integer sortOrder;
    private UUID createdBy;
    private UUID sourcePlanId;
    private UUID sourcePlanVersionId;
    private String sourcePlanMilestoneKey;
    private Integer version;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getProjectId() { return projectId; }
    public void setProjectId(UUID projectId) { this.projectId = projectId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public LocalDate getTargetDate() { return targetDate; }
    public void setTargetDate(LocalDate targetDate) { this.targetDate = targetDate; }
    public MilestoneStatus getStatus() { return status; }
    public void setStatus(MilestoneStatus status) { this.status = status; }
    public Integer getSortOrder() { return sortOrder; }
    public void setSortOrder(Integer sortOrder) { this.sortOrder = sortOrder; }
    public UUID getCreatedBy() { return createdBy; }
    public void setCreatedBy(UUID createdBy) { this.createdBy = createdBy; }
    public UUID getSourcePlanId() { return sourcePlanId; }
    public void setSourcePlanId(UUID sourcePlanId) { this.sourcePlanId = sourcePlanId; }
    public UUID getSourcePlanVersionId() { return sourcePlanVersionId; }
    public void setSourcePlanVersionId(UUID sourcePlanVersionId) { this.sourcePlanVersionId = sourcePlanVersionId; }
    public String getSourcePlanMilestoneKey() { return sourcePlanMilestoneKey; }
    public void setSourcePlanMilestoneKey(String sourcePlanMilestoneKey) { this.sourcePlanMilestoneKey = sourcePlanMilestoneKey; }
    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
