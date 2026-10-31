package com.shitulelv.aicollab.system.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record UpdateSystemConfigRequest(
        @NotNull(message = "agentEnabled 不能为空")
        Boolean agentEnabled,

        @NotNull(message = "chatEnabled 不能为空")
        Boolean chatEnabled,

        @NotNull(message = "workerConcurrency 不能为空")
        @Min(value = 1, message = "workerConcurrency 最小为 1")
        @Max(value = 4, message = "workerConcurrency 最大为 4")
        Integer workerConcurrency,

        @NotNull(message = "workerDelayMs 不能为空")
        @Min(value = 200, message = "workerDelayMs 最小为 200ms")
        @Max(value = 60000, message = "workerDelayMs 最大为 60000ms")
        Long workerDelayMs,

        @NotNull(message = "scheduleDelayMs 不能为空")
        @Min(value = 1000, message = "scheduleDelayMs 最小为 1000ms")
        @Max(value = 300000, message = "scheduleDelayMs 最大为 300000ms")
        Long scheduleDelayMs,

        @NotNull(message = "systemMaintenanceMode 不能为空")
        Boolean systemMaintenanceMode,

        @Size(max = 500, message = "系统维护通知信息长度不能超过 500 字符")
        String systemMaintenanceMessage,

        @NotNull(message = "publicRegistrationEnabled 不能为空")
        Boolean publicRegistrationEnabled,

        @Size(max = 120, message = "默认 Chat 模型名称长度不能超过 120 字符")
        String defaultChatModel,

        @Size(max = 120, message = "默认 Agent 模型名称长度不能超过 120 字符")
        String defaultAgentModel,

        @NotNull(message = "ragRetrievalThreshold 不能为空")
        @Min(value = 0, message = "ragRetrievalThreshold 最小为 0.0")
        @Max(value = 1, message = "ragRetrievalThreshold 最大为 1.0")
        Double ragRetrievalThreshold,

        @NotNull(message = "ragMaxSources 不能为空")
        @Min(value = 1, message = "ragMaxSources 最小为 1")
        @Max(value = 50, message = "ragMaxSources 最大为 50")
        Integer ragMaxSources,

        @NotNull(message = "expectedVersion 不能为空，用于并发保护")
        Long expectedVersion
) {}
