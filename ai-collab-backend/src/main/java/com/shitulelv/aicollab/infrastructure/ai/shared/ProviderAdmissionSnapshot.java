package com.shitulelv.aicollab.infrastructure.ai.shared;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Locale;

/**
 * 一次发布准入测试的持久化摘要。
 *
 * <p>{@code failureCount} 是失败样本总数，{@code timeoutCount} 是其中超时样本数，
 * 因而 {@code timeoutCount <= failureCount}。摘要必须与 provider 的
 * {@code capability_config_version} 绑定；配置版本推进后旧摘要不可用于发布。</p>
 */
public record ProviderAdmissionSnapshot(
        int schemaVersion,
        String status,
        int samples,
        long p50Ms,
        long p95Ms,
        int timeoutCount,
        int failureCount,
        long configVersion,
        long p95LimitMs) {

    public static final int SCHEMA_VERSION = 1;
    public static final String MODE = "PUBLISH_ADMISSION";
    public static final String STATUS_PASS = "PASS";
    public static final String STATUS_FAIL = "FAIL";
    public static final String STATUS_INCONCLUSIVE = "INCONCLUSIVE";

    public static ProviderAdmissionSnapshot fromJson(JsonNode node) {
        if (node == null || !node.isObject()) return null;
        return new ProviderAdmissionSnapshot(
                node.path("schemaVersion").asInt(0),
                node.path("status").asText("").toUpperCase(Locale.ROOT),
                node.path("samples").asInt(0),
                node.path("p50Ms").asLong(-1),
                node.path("p95Ms").asLong(-1),
                node.path("timeoutCount").asInt(-1),
                node.path("failureCount").asInt(-1),
                node.path("configVersion").asLong(-1),
                node.path("p95LimitMs").asLong(-1));
    }

    public boolean acceptedBy(ProviderAdmissionProperties policy) {
        return SCHEMA_VERSION == schemaVersion
                && STATUS_PASS.equals(status)
                && samples >= policy.samples()
                && p50Ms >= 0
                && p95Ms >= 0
                && p95Ms <= policy.p95MaxMs()
                && timeoutCount >= 0
                && failureCount >= 0
                && timeoutCount <= policy.maxTimeouts()
                && failureCount <= policy.maxFailures()
                && timeoutCount <= failureCount;
    }
}
