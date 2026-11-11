package com.shitulelv.aicollab.retrieval.rerank;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * 越界的 deadline / 候选数 / 影子采样率按上下界收敛；但 {@code mode} 的未知取值属于
 * "灰度开关写错"，由 {@link RerankMode#from(String)} 在绑定阶段直接让启动失败。</p>
 *
 * <p>{@code shadowSampleRate} 只影响 {@code shadow} 模式下是否发起后台 Python 对比调用，
 * 不影响用户可见结果：{@code >=1} 全采、{@code <=0} 全不采（只记
 * {@code rerank.shadow.skipped{reason="sample"}}）。越界值收敛到边界并在绑定（启动）时告警一次；
 * 非有限值（NaN/Infinity）按默认 1.0 处理。</p>
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
        @DefaultValue("1.0") double shadowSampleRate,
        @DefaultValue("") String modelUserId) {

    private static final Logger log = LoggerFactory.getLogger(RerankProperties.class);

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
        shadowSampleRate = clampSampleRate(shadowSampleRate);
    }

    /**
     * 影子采样率只做灰度观测，非法值不能阻止启动：越界收敛到 [0,1] 边界并告警一次
     * （{@code RerankProperties} 在 Spring 里是单例，构造即绑定，所以一次告警即启动告警）。
     */
    private static double clampSampleRate(double value) {
        if (!Double.isFinite(value)) {
            log.warn("rerank.shadow-sample-rate 非有限值（{}），按默认 1.0 处理", value);
            return 1.0;
        }
        if (value < 0.0) {
            log.warn("rerank.shadow-sample-rate 小于 0（{}），收敛到 0.0（不采样影调用）", value);
            return 0.0;
        }
        if (value > 1.0) {
            log.warn("rerank.shadow-sample-rate 大于 1（{}），收敛到 1.0（全采样）", value);
            return 1.0;
        }
        return value;
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
                DEFAULT_CONNECT_TIMEOUT, "", DEFAULT_MAX_CANDIDATES, DEFAULT_SHADOW_TOP_K, 1.0, "");
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
