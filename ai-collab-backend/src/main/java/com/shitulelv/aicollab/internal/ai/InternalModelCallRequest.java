package com.shitulelv.aicollab.internal.ai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Python worker → Java 内部模型网关请求（冻结契约 §2）。
 *
 * <p>{@code outputFormat} 推荐 {@code PROMPT_JSON}（应用侧解析，不要求 provider 原生
 * JSON 能力）；{@code JSON_OBJECT} 仍被接受，但供应商缺少 {@code STRUCTURED_OUTPUT}
 * 时会确定性降级为 {@code PROMPT_JSON} 重试一次。</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record InternalModelCallRequest(
        Integer schemaVersion,
        String purpose,
        String systemPrompt,
        String userPrompt,
        String outputFormat,
        Integer deadlineMs,
        String callId) {
}
