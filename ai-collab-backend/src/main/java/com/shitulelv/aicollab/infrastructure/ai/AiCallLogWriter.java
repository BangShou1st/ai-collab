package com.shitulelv.aicollab.infrastructure.ai;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class AiCallLogWriter {
    private final AiCallLogMapper mapper;

    public AiCallLogWriter(AiCallLogMapper mapper) {
        this.mapper = mapper;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void insert(
            UUID id,
            UUID userId,
            UUID projectId,
            String provider,
            String model,
            String status,
            long latencyMs,
            Integer promptTokens,
            Integer completionTokens,
            String errorCode,
            UUID requestId) {
        if (mapper.insert(
                id, userId, projectId, provider, model, status, latencyMs,
                promptTokens, completionTokens, errorCode, requestId) != 1) {
            throw new IllegalStateException("AI 调用日志写入失败");
        }
    }
}
