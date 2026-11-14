package com.shitulelv.aicollab.retrieval.application;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * 知识问答检索模式配置。
 *
 * <p>{@code retrieval.qa-mode} 默认 {@code DENSE_LEGACY}，保持既有生产行为；
 * 仅显式切换为 {@code STRATEGY_AWARE} 时，问答与流式问答才进入策略感知检索执行器。</p>
 */
@Component
@ConfigurationProperties("retrieval")
public record RetrievalQaProperties(
        @DefaultValue("DENSE_LEGACY") String qaMode) {

    public RetrievalQaProperties {
        qaMode = qaMode == null ? "DENSE_LEGACY" : qaMode.strip().toUpperCase(Locale.ROOT);
        if (!"DENSE_LEGACY".equals(qaMode) && !"STRATEGY_AWARE".equals(qaMode)) {
            throw new IllegalArgumentException(
                    "retrieval.qa-mode 只允许 DENSE_LEGACY 或 STRATEGY_AWARE");
        }
    }

    public ProjectKnowledgeRetrieval.Mode mode() {
        return ProjectKnowledgeRetrieval.Mode.valueOf(qaMode);
    }

    public static RetrievalQaProperties defaults() {
        return new RetrievalQaProperties("DENSE_LEGACY");
    }
}
