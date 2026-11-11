package com.shitulelv.aicollab.retrieval.rerank;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * Rerank 模式与内部 ai-worker 调用配置（W3 冻结契约 §2/§3）。
 *
 * <p><b>默认值必须是"行为不变"的安全默认</b>：{@code mode=java} 时检索管线完全走
 * 现有 Java 重排器，不发起任何外呼；{@code service-token}/{@code model-user-id}
 * 默认空，未配置时内部模型网关 fail closed。</p>
 *
 * <p>配置错误不应阻止应用启动（与 {@code PromptRegistryProperties} 同一约定）：
 * 越界的 deadline / 候选数按上下界收敛；但 {@code mode} 的未知取值属于"灰度开关写错"，
 * 由 {@link RerankMode#from(String)} 在绑定阶段直接让启动失败。</p>
 */
@ConfigurationProperties("rerank")
public record RerankProperties(
        @DefaultValue("java") RerankMode mode,
        @DefaultValue("http://ai-worker-api:9100") String workerBaseUrl,
        @DefaultValue("3000") int deadlineMs,
        @DefaultValue("2s") Duration connectTimeout,
        @DefaultValue("") String serviceToken,
        @DefaultValue("20") int maxCandidates,
        @DefaultValue("8") int shadowTopK,
        @DefaultValue("") String modelUserId) {

    private static final String DEFAULT_WORKER_BASE_URL = "http://ai-worker-api:9100";
    private static final int DEFAULT_DEADLINE_MS = 3000;
    private static final int MAX_DEADLINE_MS = 30000;
    private static final int MAX_CANDIDATES = 50;
    private static final int DEFAULT_MAX_CANDIDATES = 20;
    private static final int DEFAULT_SHADOW_TOP_K = 8;
    private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(2);

    public RerankProperties {
        if (mode == null) {
            mode = RerankMode.JAVA;
        }
        workerBaseUrl = normalizeBaseUrl(workerBaseUrl);
        if (deadlineMs < 1) {
            deadlineMs = DEFAULT_DEADLINE_MS;
        } else if (deadlineMs > MAX_DEADLINE_MS) {
            deadlineMs = MAX_DEADLINE_MS;
        }
        if (connectTimeout == null || connectTimeout.isZero() || connectTimeout.isNegative()) {
            connectTimeout = DEFAULT_CONNECT_TIMEOUT;
        }
        serviceToken = serviceToken == null ? "" : serviceToken.trim();
        maxCandidates = clamp(maxCandidates, 1, MAX_CANDIDATES, DEFAULT_MAX_CANDIDATES);
        shadowTopK = clamp(shadowTopK, 1, MAX_CANDIDATES, DEFAULT_SHADOW_TOP_K);
        modelUserId = modelUserId == null ? "" : modelUserId.trim();
    }

    /**
     * 内部模型网关使用的服务调用账号。
     *
     * <p>这是本波对"预算/账本归属"的临时落实（现有 {@code ChatModelGateway} 必须持有非空
     * {@code callerUserId}），登记为遗留：W4 换系统共享模型档案后删除。
     * 未配置或格式非法都返回 empty，由调用方按 {@code MODEL_UNAVAILABLE} fail closed。</p>
     */
    public Optional<UUID> modelUserIdAsUuid() {
        if (modelUserId.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(modelUserId));
        } catch (IllegalArgumentException invalid) {
            return Optional.empty();
        }
    }

    /** 默认值，供不依赖 Spring Binder 的代码与测试使用。 */
    public static RerankProperties defaults() {
        return new RerankProperties(RerankMode.JAVA, DEFAULT_WORKER_BASE_URL, DEFAULT_DEADLINE_MS,
                DEFAULT_CONNECT_TIMEOUT, "", DEFAULT_MAX_CANDIDATES, DEFAULT_SHADOW_TOP_K, "");
    }

    private static String normalizeBaseUrl(String raw) {
        String value = raw == null ? "" : raw.trim();
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value.isEmpty() ? DEFAULT_WORKER_BASE_URL : value;
    }

    private static int clamp(int value, int min, int max, int fallback) {
        if (value < min || value > max) {
            return fallback;
        }
        return value;
    }
}
