package com.shitulelv.aicollab.system.infrastructure.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.shitulelv.aicollab.system.domain.model.SystemConfig;

import java.time.OffsetDateTime;
import java.util.UUID;

@TableName("system_configuration")
public class SystemConfigEntity {
    @TableId(type = IdType.INPUT)
    private UUID id;
    private Boolean agentEnabled;
    private Boolean chatEnabled;
    private Integer workerConcurrency;
    private Long workerDelayMs;
    private Long scheduleDelayMs;
    private Boolean systemMaintenanceMode;
    private String systemMaintenanceMessage;
    private Boolean publicRegistrationEnabled;
    private String defaultChatModel;
    private String defaultAgentModel;
    private Double ragRetrievalThreshold;
    private Integer ragMaxSources;
    private Long version;
    private UUID updatedBy;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;

    public SystemConfigEntity() {}

    public static SystemConfigEntity fromDomain(SystemConfig config) {
        SystemConfigEntity entity = new SystemConfigEntity();
        entity.id = config.id();
        entity.agentEnabled = config.agentEnabled();
        entity.chatEnabled = config.chatEnabled();
        entity.workerConcurrency = config.workerConcurrency();
        entity.workerDelayMs = config.workerDelayMs();
        entity.scheduleDelayMs = config.scheduleDelayMs();
        entity.systemMaintenanceMode = config.systemMaintenanceMode();
        entity.systemMaintenanceMessage = config.systemMaintenanceMessage();
        entity.publicRegistrationEnabled = config.publicRegistrationEnabled();
        entity.defaultChatModel = config.defaultChatModel();
        entity.defaultAgentModel = config.defaultAgentModel();
        entity.ragRetrievalThreshold = config.ragRetrievalThreshold();
        entity.ragMaxSources = config.ragMaxSources();
        entity.version = config.version();
        entity.updatedBy = config.updatedBy();
        entity.createdAt = config.createdAt();
        entity.updatedAt = config.updatedAt();
        return entity;
    }

    public SystemConfig toDomain() {
        return new SystemConfig(
                id,
                Boolean.TRUE.equals(agentEnabled),
                Boolean.TRUE.equals(chatEnabled),
                workerConcurrency != null ? workerConcurrency : 2,
                workerDelayMs != null ? workerDelayMs : 1000L,
                scheduleDelayMs != null ? scheduleDelayMs : 30000L,
                Boolean.TRUE.equals(systemMaintenanceMode),
                systemMaintenanceMessage != null ? systemMaintenanceMessage : "",
                publicRegistrationEnabled == null || publicRegistrationEnabled,
                defaultChatModel != null ? defaultChatModel : "",
                defaultAgentModel != null ? defaultAgentModel : "",
                ragRetrievalThreshold != null ? ragRetrievalThreshold : 0.55,
                ragMaxSources != null ? ragMaxSources : 12,
                version != null ? version : 1L,
                updatedBy,
                createdAt,
                updatedAt
        );
    }

    // Getters and setters
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public Boolean getAgentEnabled() { return agentEnabled; }
    public void setAgentEnabled(Boolean agentEnabled) { this.agentEnabled = agentEnabled; }
    public Boolean getChatEnabled() { return chatEnabled; }
    public void setChatEnabled(Boolean chatEnabled) { this.chatEnabled = chatEnabled; }
    public Integer getWorkerConcurrency() { return workerConcurrency; }
    public void setWorkerConcurrency(Integer workerConcurrency) { this.workerConcurrency = workerConcurrency; }
    public Long getWorkerDelayMs() { return workerDelayMs; }
    public void setWorkerDelayMs(Long workerDelayMs) { this.workerDelayMs = workerDelayMs; }
    public Long getScheduleDelayMs() { return scheduleDelayMs; }
    public void setScheduleDelayMs(Long scheduleDelayMs) { this.scheduleDelayMs = scheduleDelayMs; }
    public Boolean getSystemMaintenanceMode() { return systemMaintenanceMode; }
    public void setSystemMaintenanceMode(Boolean systemMaintenanceMode) { this.systemMaintenanceMode = systemMaintenanceMode; }
    public String getSystemMaintenanceMessage() { return systemMaintenanceMessage; }
    public void setSystemMaintenanceMessage(String systemMaintenanceMessage) { this.systemMaintenanceMessage = systemMaintenanceMessage; }
    public Boolean getPublicRegistrationEnabled() { return publicRegistrationEnabled; }
    public void setPublicRegistrationEnabled(Boolean publicRegistrationEnabled) { this.publicRegistrationEnabled = publicRegistrationEnabled; }
    public String getDefaultChatModel() { return defaultChatModel; }
    public void setDefaultChatModel(String defaultChatModel) { this.defaultChatModel = defaultChatModel; }
    public String getDefaultAgentModel() { return defaultAgentModel; }
    public void setDefaultAgentModel(String defaultAgentModel) { this.defaultAgentModel = defaultAgentModel; }
    public Double getRagRetrievalThreshold() { return ragRetrievalThreshold; }
    public void setRagRetrievalThreshold(Double ragRetrievalThreshold) { this.ragRetrievalThreshold = ragRetrievalThreshold; }
    public Integer getRagMaxSources() { return ragMaxSources; }
    public void setRagMaxSources(Integer ragMaxSources) { this.ragMaxSources = ragMaxSources; }
    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
    public UUID getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(UUID updatedBy) { this.updatedBy = updatedBy; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
