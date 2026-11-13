package com.shitulelv.aicollab.infrastructure.ai.shared.api;

import com.shitulelv.aicollab.infrastructure.ai.ChatCompletionResult;
import com.shitulelv.aicollab.infrastructure.ai.shared.ProviderAdmissionSnapshot;

/**
 * 管理员连接测试响应：保留原有 {@code content/provider/model/...} 字段兼容前端，
 * 新增本次多次采样的准入摘要。
 */
public record AdminAiProviderTestView(
        String content,
        String provider,
        String model,
        Integer promptTokens,
        Integer completionTokens,
        long latencyMs,
        AdmissionView admission) {

    public record AdmissionView(
            boolean accepted,
            String status,
            int samples,
            long p50Ms,
            long p95Ms,
            int timeoutCount,
            int failureCount,
            long configVersion,
            long p95LimitMs) {
    }

    public static AdminAiProviderTestView from(
            ChatCompletionResult result, ProviderAdmissionSnapshot snapshot, boolean accepted) {
        return new AdminAiProviderTestView(
                result.content(),
                result.provider(),
                result.model(),
                result.promptTokens(),
                result.completionTokens(),
                result.latencyMs(),
                new AdmissionView(
                        accepted,
                        snapshot.status(),
                        snapshot.samples(),
                        snapshot.p50Ms(),
                        snapshot.p95Ms(),
                        snapshot.timeoutCount(),
                        snapshot.failureCount(),
                        snapshot.configVersion(),
                        snapshot.p95LimitMs()));
    }
}
