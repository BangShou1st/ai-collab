package com.shitulelv.aicollab.retrieval.application;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.Locale;

/**
 * 知识问答检索配置。
 *
 * <p>{@code retrieval.qa-mode} 默认 {@code STRATEGY_AWARE}（统一走策略感知执行器）；
 * {@code retrieval.force-legacy-dense} 为紧急回退开关，仅在排障需要全站临时降级为旧版 Dense 时设为 true。</p>
 */
@ConfigurationProperties("retrieval")
public record RetrievalQaProperties(
        @DefaultValue("STRATEGY_AWARE") String qaMode,
        @DefaultValue("false") boolean forceLegacyDense) {

    @ConstructorBinding
    public RetrievalQaProperties {
        qaMode = qaMode == null ? "STRATEGY_AWARE" : qaMode.strip().toUpperCase(Locale.ROOT);
        if (!"DENSE_LEGACY".equals(qaMode) && !"STRATEGY_AWARE".equals(qaMode)) {
            throw new IllegalArgumentException(
                    "retrieval.qa-mode 只允许 DENSE_LEGACY 或 STRATEGY_AWARE");
        }
    }

    public RetrievalQaProperties(String qaMode) {
        this(qaMode, false);
    }

    public LegacyProjectKnowledgeRetrieval.Mode mode() {
        return LegacyProjectKnowledgeRetrieval.Mode.valueOf(qaMode);
    }

    public static RetrievalQaProperties defaults() {
        return new RetrievalQaProperties("STRATEGY_AWARE", false);
    }
}
