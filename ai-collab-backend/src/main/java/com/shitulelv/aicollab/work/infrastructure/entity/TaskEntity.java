package com.shitulelv.aicollab.work.infrastructure.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.shitulelv.aicollab.work.domain.model.TaskPriority;
import com.shitulelv.aicollab.work.domain.model.TaskStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

@TableName("project_task")
public class TaskEntity {
    @TableId private UUID id;
    private UUID projectId;
    private UUID milestoneId;
    private String title;
    private String description;
    private TaskStatus status;
    private TaskPriority priority;
    private UUID assigneeId;
    private BigDecimal estimateHours;
    private LocalDate startDate;
    private LocalDate dueDate;
    private OffsetDateTime completedAt;
    private Integer sortOrder;
    private UUID createdBy;
    private Integer version;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
    @TableField(exist = false) private Integer unfinishedDependencyCount;
    @TableField(exist = false) private String assigneeDisplayName;
    @TableField(exist = false) private String milestoneName;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getProjectId() { return projectId; }
    public void setProjectId(UUID projectId) { this.projectId = projectId; }
    public UUID getMilestoneId() { return milestoneId; }
    public void setMilestoneId(UUID milestoneId) { this.milestoneId = milestoneId; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public TaskStatus getStatus() { return status; }
    public void setStatus(TaskStatus status) { this.status = status; }
    public TaskPriority getPriority() { return priority; }
    public void setPriority(TaskPriority priority) { this.priority = priority; }
    public UUID getAssigneeId() { return assigneeId; }
    public void setAssigneeId(UUID assigneeId) { this.assigneeId = assigneeId; }
    public BigDecimal getEstimateHours() { return estimateHours; }
    public void setEstimateHours(BigDecimal estimateHours) { this.estimateHours = estimateHours; }
    public LocalDate getStartDate() { return startDate; }
    public void setStartDate(LocalDate startDate) { this.startDate = startDate; }
    public LocalDate getDueDate() { return dueDate; }
    public void setDueDate(LocalDate dueDate) { this.dueDate = dueDate; }
    public OffsetDateTime getCompletedAt() { return completedAt; }
    public void setCompletedAt(OffsetDateTime completedAt) { this.completedAt = completedAt; }
    public Integer getSortOrder() { return sortOrder; }
    public void setSortOrder(Integer sortOrder) { this.sortOrder = sortOrder; }
    public UUID getCreatedBy() { return createdBy; }
    public void setCreatedBy(UUID createdBy) { this.createdBy = createdBy; }
    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
    public Integer getUnfinishedDependencyCount() { return unfinishedDependencyCount; }
    public void setUnfinishedDependencyCount(Integer value) { this.unfinishedDependencyCount = value; }
    public String getAssigneeDisplayName() { return assigneeDisplayName; }
    public void setAssigneeDisplayName(String value) { this.assigneeDisplayName = value; }
    public String getMilestoneName() { return milestoneName; }
    public void setMilestoneName(String value) { this.milestoneName = value; }
}
