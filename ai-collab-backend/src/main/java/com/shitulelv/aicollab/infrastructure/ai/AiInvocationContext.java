package com.shitulelv.aicollab.infrastructure.ai;

import com.shitulelv.aicollab.infrastructure.ai.model.ModelPurpose;

import java.util.UUID;

/**
 * AI 调用上下文：Credential 归属永远是 userId，projectId 只是业务上下文。
 * userId 禁止塞进 Map 或 ThreadLocal，必须显式传递。
 */
public record AiInvocationContext(
        UUID userId,
        UUID projectId,
        ModelPurpose purpose) {
}
