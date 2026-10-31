package com.shitulelv.aicollab.system.domain.policy;

import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.system.domain.model.SystemConfig;

/**
 * 系统配置合法性与安全边界校验。
 */
public final class SystemConfigValidator {

    private SystemConfigValidator() {}

    public static void validate(
            int workerConcurrency,
            long workerDelayMs,
            long scheduleDelayMs,
            String systemMaintenanceMessage,
            String defaultChatModel,
            String defaultAgentModel,
            double ragRetrievalThreshold,
            int ragMaxSources
    ) {
        if (workerConcurrency < SystemConfig.MIN_CONCURRENCY || workerConcurrency > SystemConfig.MAX_CONCURRENCY) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "Worker 并发数必须在 " + SystemConfig.MIN_CONCURRENCY + " 到 " + SystemConfig.MAX_CONCURRENCY + " 之间");
        }
        if (workerDelayMs < SystemConfig.MIN_WORKER_DELAY || workerDelayMs > SystemConfig.MAX_WORKER_DELAY) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "Worker 调度间隔必须在 " + SystemConfig.MIN_WORKER_DELAY + "ms 到 " + SystemConfig.MAX_WORKER_DELAY + "ms 之间");
        }
        if (scheduleDelayMs < SystemConfig.MIN_SCHEDULE_DELAY || scheduleDelayMs > SystemConfig.MAX_SCHEDULE_DELAY) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "定时任务检测间隔必须在 " + SystemConfig.MIN_SCHEDULE_DELAY + "ms 到 " + SystemConfig.MAX_SCHEDULE_DELAY + "ms 之间");
        }
        if (systemMaintenanceMessage != null && systemMaintenanceMessage.length() > 500) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "系统维护通知信息长度不能超过 500 字符");
        }
        if (defaultChatModel != null && defaultChatModel.length() > 120) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "默认 Chat 模型名称长度不能超过 120 字符");
        }
        if (defaultAgentModel != null && defaultAgentModel.length() > 120) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "默认 Agent 模型名称长度不能超过 120 字符");
        }
        if (ragRetrievalThreshold < SystemConfig.MIN_RAG_THRESHOLD || ragRetrievalThreshold > SystemConfig.MAX_RAG_THRESHOLD) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "RAG 检索相似度阈值必须在 " + SystemConfig.MIN_RAG_THRESHOLD + " 到 " + SystemConfig.MAX_RAG_THRESHOLD + " 之间");
        }
        if (ragMaxSources < SystemConfig.MIN_RAG_SOURCES || ragMaxSources > SystemConfig.MAX_RAG_SOURCES) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "RAG 最大召回片段数量必须在 " + SystemConfig.MIN_RAG_SOURCES + " 到 " + SystemConfig.MAX_RAG_SOURCES + " 之间");
        }
    }
}
