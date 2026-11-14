package com.shitulelv.aicollab.document.infrastructure.diagnostics;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * 文档来源范围解析结果计数（J1-2-P1）。
 *
 * <p>背景：设计 §5.1 要求删除 {@code DocumentSearchService} 的中文来源正则，前置是"显式
 * documentIds"。删之前必须知道这条正则到底在被依赖多少——{@code ENFORCED}（真的收窄了范围）与
 * {@code UNRESOLVED_INCOMPLETE}（已经 fail-closed、用户在等澄清）的占比，决定删除后的回归面与
 * 是否需要先做前端/模型侧的替代通道。本指标只增加观测，不改变任何解析或检索行为。</p>
 *
 * <p>口径：每次 {@code resolveUserDocumentScope} 调用记一次，因此计的是"工具调用次数"而不是
 * "用户请求次数"——Agent 的 list/outline/read/search 四个工具都会各调用一次，适合看比例，
 * 不适合直接当请求量。取值只有 {@link com.shitulelv.aicollab.document.application.service
 * .DocumentSearchService.ScopeStatus} 的四个枚举名，其余一律 {@code UNKNOWN}（未知即未知）。</p>
 */
@Component
public class DocumentScopeMetrics {
    /** 指标名，供文档与测试引用（改名前先查文档）。 */
    public static final String COUNTER = "agent.document.scope";

    private final MeterRegistry registry;

    public DocumentScopeMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** 记录一次范围解析结果；指标故障不得改变检索行为。 */
    public void scope(String status) {
        try {
            registry.counter(COUNTER, "status", normalize(status)).increment();
        } catch (RuntimeException ignored) {
            // Metrics cannot change business outcomes.
        }
    }

    /** 只接受 ScopeStatus 的四个枚举名；null、空串、未知大小写一律归 UNKNOWN。 */
    static String normalize(String status) {
        return switch (status == null ? "" : status) {
            case "UNRESTRICTED", "ENFORCED", "EMPTY", "UNRESOLVED_INCOMPLETE" -> status;
            default -> "UNKNOWN";
        };
    }
}
