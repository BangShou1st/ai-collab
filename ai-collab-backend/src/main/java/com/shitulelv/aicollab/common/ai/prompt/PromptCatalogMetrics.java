package com.shitulelv.aicollab.common.ai.prompt;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

import java.util.Objects;
import java.util.function.ToDoubleFunction;

/**
 * PromptCatalog 的 Micrometer 指标出口（T5）。
 *
 * <p>只有三个低基数指标：</p>
 * <ul>
 *   <li>{@code prompt.catalog.refresh{result=success|failure}}：仅远端目录有刷新；</li>
 *   <li>{@code prompt.catalog.unavailable{key}}：{@code require} 快速失败时计数；</li>
 *   <li>{@code prompt.catalog.entries{source}}：当前可用条目数（gauge）。</li>
 * </ul>
 *
 * <p>指标绝不能改变业务结果：任何 Micrometer 运行时异常都在这里吞掉，
 * 与 {@code AgentRuntimeMetrics} 的既有边界一致。注入的 registry 不允许为 null；
 * 无容器场景由调用方传 {@link io.micrometer.core.instrument.Metrics#globalRegistry}
 * （无子 registry 时等价于 no-op），因此业务代码里不需要 null 分支。</p>
 */
final class PromptCatalogMetrics {

    private static final String REFRESH = "prompt.catalog.refresh";
    private static final String UNAVAILABLE = "prompt.catalog.unavailable";
    private static final String ENTRIES = "prompt.catalog.entries";

    private final MeterRegistry registry;

    PromptCatalogMetrics(MeterRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "meterRegistry");
    }

    void refresh(boolean success) {
        try {
            registry.counter(REFRESH, "result", success ? "success" : "failure").increment();
        } catch (RuntimeException ignored) {
            // 指标不得改变业务结果
        }
    }

    void unavailable(String key) {
        try {
            registry.counter(UNAVAILABLE, "key", key).increment();
        } catch (RuntimeException ignored) {
            // 指标不得改变业务结果
        }
    }

    <T> void entries(String source, T state, ToDoubleFunction<T> count) {
        try {
            Gauge.builder(ENTRIES, state, count::applyAsDouble)
                    .tag("source", source)
                    .register(registry);
        } catch (RuntimeException ignored) {
            // 指标不得改变业务结果
        }
    }
}
