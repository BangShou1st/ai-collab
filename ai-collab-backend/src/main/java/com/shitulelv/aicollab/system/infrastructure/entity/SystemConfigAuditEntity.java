package com.shitulelv.aicollab.system.infrastructure.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.shitulelv.aicollab.system.domain.model.SystemConfigAudit;

import java.time.OffsetDateTime;
import java.util.UUID;

@TableName("system_config_audit")
public class SystemConfigAuditEntity {
    @TableId(type = IdType.INPUT)
    private UUID id;
    private Long configVersion;
    private String action;
    private UUID changedBy;
    private String changedByName;
    private String changeSummary;
    private String configSnapshot;
    private String appliedStatus;
    private String errorMessage;
    private OffsetDateTime createdAt;

    public SystemConfigAuditEntity() {}

    public static SystemConfigAuditEntity fromDomain(SystemConfigAudit audit) {
        SystemConfigAuditEntity entity = new SystemConfigAuditEntity();
        entity.id = audit.id();
        entity.configVersion = audit.configVersion();
        entity.action = audit.action();
        entity.changedBy = audit.changedBy();
        entity.changedByName = audit.changedByName();
        entity.changeSummary = audit.changeSummary();
        entity.configSnapshot = audit.configSnapshot();
        entity.appliedStatus = audit.appliedStatus();
        entity.errorMessage = audit.errorMessage();
        entity.createdAt = audit.createdAt();
        return entity;
    }

    public SystemConfigAudit toDomain() {
        return new SystemConfigAudit(
                id,
                configVersion,
                action,
                changedBy,
                changedByName,
                changeSummary,
                configSnapshot,
                appliedStatus,
                errorMessage,
                createdAt
        );
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public Long getConfigVersion() { return configVersion; }
    public void setConfigVersion(Long configVersion) { this.configVersion = configVersion; }
    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
    public UUID getChangedBy() { return changedBy; }
    public void setChangedBy(UUID changedBy) { this.changedBy = changedBy; }
    public String getChangedByName() { return changedByName; }
    public void setChangedByName(String changedByName) { this.changedByName = changedByName; }
    public String getChangeSummary() { return changeSummary; }
    public void setChangeSummary(String changeSummary) { this.changeSummary = changeSummary; }
    public String getConfigSnapshot() { return configSnapshot; }
    public void setConfigSnapshot(String configSnapshot) { this.configSnapshot = configSnapshot; }
    public String getAppliedStatus() { return appliedStatus; }
    public void setAppliedStatus(String appliedStatus) { this.appliedStatus = appliedStatus; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
