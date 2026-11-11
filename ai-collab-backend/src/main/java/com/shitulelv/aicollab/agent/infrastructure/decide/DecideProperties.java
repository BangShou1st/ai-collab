package com.shitulelv.aicollab.agent.infrastructure.decide;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Agent decide shadow 配置（W4 冻结契约 §3/§6）。
 *
 * <p><b>默认必须是"行为不变"的安全默认</b>：{@code mode=java} 时不构造快照、
 * 不发起任何外呼、不记录任何 decide 指标。</p>
 *
 * <p>配置错误不应阻止应用启动（与 {@code RerankProperties} 同一约定）：越界的
 * deadline / 并发 / 工具数 / 快照字符数按上下界收敛；但 {@code mode} 的未知取值
 * 与尚未实现的 {@code python} 必须让启动失败：未知值由枚举转换拒绝，
 * {@code python} 由 {@link DecideMode#from(String)}（显式转换器）与本记录构造器
 * 双重拒绝——Boot 的宽松枚举转换可能先于自定义转换器生效，构造器是最后一道防线。</p>
 *
 * <p><b>服务令牌不在此处声明</b>：本波复用 {@code rerank.service-token}
 * （{@code RerankProperties#serviceToken()}），由 HTTP 客户端读取；W3.2 之后
 * 内部令牌提供者上线时只替换客户端里的取令牌方法。</p>
 */
@ConfigurationProperties("decide")
public record DecideProperties(
        @DefaultValue("java") DecideMode mode,
        @DefaultValue("http://ai-worker-api:9100") String workerBaseUrl,
        @DefaultValue("30000") int deadlineMs,
        @DefaultValue("8") int maxConcurrency,
        @DefaultValue("16") int maxTools,
        @DefaultValue("32000") int snapshotMaxChars) {

    private static final String DEFAULT_WORKER_BASE_URL = "http://ai-worker-api:9100";
    private static final int DEFAULT_DEADLINE_MS = 30000;
    private static final int MIN_DEADLINE_MS = 1;
    private static final int MAX_DEADLINE_MS = 600000;
    private static final int DEFAULT_MAX_CONCURRENCY = 8;
    private static final int MIN_MAX_CONCURRENCY = 1;
    private static final int MAX_MAX_CONCURRENCY = 64;
    private static final int DEFAULT_MAX_TOOLS = 16;
    private static final int MIN_MAX_TOOLS = 1;
    private static final int MAX_MAX_TOOLS = 32;
    private static final int DEFAULT_SNAPSHOT_MAX_CHARS = 32000;
    /** 低于该值时连"一个有界 goal + 一个 inputSchema≤2000 的工具"都装不下。 */
    private static final int MIN_SNAPSHOT_MAX_CHARS = 4000;
    private static final int MAX_SNAPSHOT_MAX_CHARS = 200000;

    public DecideProperties {
        if (mode == null) {
            mode = DecideMode.JAVA;
        }
        if (mode == DecideMode.PYTHON) {
            // 第二道防线：Spring Boot 的宽松枚举转换可能先于自定义 Converter 把 python
            // 绑定成 DecideMode.PYTHON，因此构造器必须再次拒绝，确保"配置即启动失败"。
            throw new IllegalStateException(
                    "decide.mode=python 模式 W4 未实现，拒绝启动；允许取值：java | shadow");
        }
        workerBaseUrl = normalizeBaseUrl(workerBaseUrl);
        deadlineMs = clamp(deadlineMs, MIN_DEADLINE_MS, MAX_DEADLINE_MS, DEFAULT_DEADLINE_MS);
        maxConcurrency = clamp(maxConcurrency, MIN_MAX_CONCURRENCY, MAX_MAX_CONCURRENCY,
                DEFAULT_MAX_CONCURRENCY);
        maxTools = clamp(maxTools, MIN_MAX_TOOLS, MAX_MAX_TOOLS, DEFAULT_MAX_TOOLS);
        snapshotMaxChars = clamp(snapshotMaxChars, MIN_SNAPSHOT_MAX_CHARS, MAX_SNAPSHOT_MAX_CHARS,
                DEFAULT_SNAPSHOT_MAX_CHARS);
    }

    /** 默认值，供不依赖 Spring Binder 的代码与测试使用。 */
    public static DecideProperties defaults() {
        return new DecideProperties(DecideMode.JAVA, DEFAULT_WORKER_BASE_URL, DEFAULT_DEADLINE_MS,
                DEFAULT_MAX_CONCURRENCY, DEFAULT_MAX_TOOLS, DEFAULT_SNAPSHOT_MAX_CHARS);
    }

    private static String normalizeBaseUrl(String raw) {
        String value = raw == null ? "" : raw.trim();
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value.isEmpty() ? DEFAULT_WORKER_BASE_URL : value;
    }

    /** 越界值按边界收敛（而不是悄悄回退到默认值）。 */
    private static int clamp(int value, int min, int max, int fallback) {
        if (value == Integer.MIN_VALUE || value == Integer.MAX_VALUE) {
            // 溢出/哨兵值不具备运维含义，回退到默认值。
            return fallback;
        }
        if (value < min) {
            return min;
        }
        return Math.min(value, max);
    }
}
