package com.shitulelv.aicollab.retrieval.remote;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Python 检索接口配置（P2 契约 §4.4）。deadline 按 1..30000ms 收敛；baseUrl 去掉末尾斜杠。
 */
@ConfigurationProperties("knowledge.search")
public record KnowledgeSearchProperties(
        @DefaultValue("http://ai-worker:9100") String workerBaseUrl,
        @DefaultValue("8000") int deadlineMs) {

    private static final String DEFAULT_WORKER_BASE_URL = "http://ai-worker:9100";

    public KnowledgeSearchProperties {
        workerBaseUrl = workerBaseUrl == null || workerBaseUrl.isBlank()
                ? DEFAULT_WORKER_BASE_URL : workerBaseUrl.strip().replaceAll("/+$", "");
        deadlineMs = Math.max(1, Math.min(deadlineMs, 30000));
    }

    public static KnowledgeSearchProperties defaults() {
        return new KnowledgeSearchProperties(DEFAULT_WORKER_BASE_URL, 8000);
    }
}
