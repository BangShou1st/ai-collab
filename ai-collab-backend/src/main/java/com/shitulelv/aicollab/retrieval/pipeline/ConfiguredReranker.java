package com.shitulelv.aicollab.retrieval.pipeline;

import com.shitulelv.aicollab.infrastructure.ai.capability.CapabilityProvider;
import com.shitulelv.aicollab.infrastructure.ai.capability.LlamaCppRerankAdapter;
import com.shitulelv.aicollab.infrastructure.ai.capability.RerankCallResult;
import com.shitulelv.aicollab.infrastructure.ai.capability.RerankCapabilityResolver;
import com.shitulelv.aicollab.infrastructure.ai.capability.RerankDocument;
import com.shitulelv.aicollab.infrastructure.ai.capability.RerankScore;
import com.shitulelv.aicollab.infrastructure.ai.capability.ResolvedRerankConfiguration;
import com.shitulelv.aicollab.infrastructure.ai.capability.RerankSettings;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelSecretCipher;
import com.shitulelv.aicollab.retrieval.rerank.RerankExecutionPolicy;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 显式选择的能力配置重排器（{@link RerankerType#CONFIGURED_RERANK}）。
 *
 * <p>默认检索管线仍使用 {@link RerankerType#LEXICAL_HEURISTIC}；本实现只有在显式选择
 * 新类型时才生效。没有项目/全局绑定、profile 未发布、Provider 不可用、超时、非法响应
 * 或凭据不可读时，均回退 {@link SemanticHeuristicReranker} 并记录
 * {@code rerank.fallback{reason=...}}，绝不抛给主请求。</p>
 *
 * <p>日志不包含 query、候选正文、密钥、Provider endpoint 或模型文件路径。</p>
 */
@Component
public class ConfiguredReranker implements Reranker {

    public static final String NAME = "configured-rerank";

    private static final Logger log = LoggerFactory.getLogger(ConfiguredReranker.class);
    private static final double EPSILON = 1e-9;
    private static final String METRIC_FALLBACK = "rerank.fallback";
    private static final String METRIC_CALLS = "rerank.configured.calls";
    private static final String METRIC_TOTAL = "rerank.configured.total";
    private static final String METRIC_MODEL = "rerank.configured.model";

    private final RerankCapabilityResolver resolver;
    private final LlamaCppRerankAdapter adapter;
    private final SemanticHeuristicReranker fallback;
    private final ModelSecretCipher secrets;
    private final MeterRegistry meterRegistry;

    public ConfiguredReranker(RerankCapabilityResolver resolver, LlamaCppRerankAdapter adapter,
            SemanticHeuristicReranker fallback, ModelSecretCipher secrets, MeterRegistry meterRegistry) {
        this.resolver = resolver;
        this.adapter = adapter;
        this.fallback = fallback != null ? fallback : new SemanticHeuristicReranker();
        this.secrets = secrets;
        this.meterRegistry = meterRegistry;
    }

    /** 为检索管线提供项目作用域实例；全局/其它调用使用本 Bean 自身。 */
    public Reranker forProject(UUID projectId) {
        return new ProjectScopedReranker(projectId);
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public List<RetrievalCandidate> rerank(String query, List<RetrievalCandidate> candidates, int topN) {
        return rerankScoped(null, query, candidates, topN);
    }

    private List<RetrievalCandidate> rerankScoped(UUID projectId, String query,
            List<RetrievalCandidate> candidates, int topN) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        long startNanos = System.nanoTime();
        if (!RerankExecutionPolicy.inlineAllowed(candidates.size())) {
            recordCall("fallback");
            recordFallback(RerankExecutionPolicy.INLINE_CANDIDATE_LIMIT_REASON);
            log.warn("configured rerank 拒绝超过 8 候选的 inline 调用，回退 Java 启发式: "
                    + "projectId={} candidates={}", projectId, candidates.size());
            return safeFallback(query, candidates, topN,
                    RerankExecutionPolicy.INLINE_CANDIDATE_LIMIT_REASON);
        }
        RerankCapabilityResolver.Resolution resolution;
        try {
            resolution = resolver.resolve(projectId);
        } catch (RuntimeException failure) {
            recordCall("fallback");
            return safeFallback(query, candidates, topN, "resolve_error");
        }
        if (!resolution.available()) {
            recordCall("fallback");
            if ("no_assignment".equals(resolution.fallbackReason())) {
                log.debug("configured rerank 无绑定，回退 Java 启发式: projectId={} candidates={}",
                        projectId, candidates.size());
            } else {
                log.warn("configured rerank 解析失败，回退 Java 启发式: projectId={} reason={} candidates={}",
                        projectId, resolution.fallbackReason(), candidates.size());
            }
            recordFallback(resolution.fallbackReason());
            return safeFallback(query, candidates, topN, resolution.fallbackReason());
        }

        ResolvedRerankConfiguration configuration = resolution.configuration();
        RerankSettings settings = RerankExecutionPolicy.inlineSettings(configuration.settings());
        int processed = Math.min(candidates.size(), settings.maxCandidates());
        List<RetrievalCandidate> target = candidates.subList(0, processed);
        List<RerankDocument> documents = new ArrayList<>(target.size());
        for (RetrievalCandidate candidate : target) {
            documents.add(new RerankDocument(candidate.id().toString(),
                    candidate.heading() == null ? "" : candidate.heading(),
                    candidate.effectiveContent()));
        }
        String apiKey = null;
        CapabilityProvider provider = configuration.provider();
        if (provider.requiresAuth()) {
            try {
                apiKey = secrets.decryptOptional(provider.encryptedApiKey());
            } catch (RuntimeException unreadable) {
                apiKey = null;
            }
            if (apiKey == null || apiKey.isBlank()) {
                recordCall("fallback");
                recordFallback("credential_unavailable");
                return safeFallback(query, candidates, topN, "credential_unavailable");
            }
        }

        RerankCallResult result;
        try {
            result = adapter.rerank(provider, settings, query, documents, settings.topK(), apiKey);
        } catch (RuntimeException failure) {
            recordCall("fallback");
            recordFallback("adapter_error");
            log.warn("configured rerank adapter 异常: profileId={} providerId={} type={}",
                    configuration.profile().id(), provider.id(), failure.getClass().getSimpleName());
            return safeFallback(query, candidates, topN, "adapter_error");
        }
        recordModelDuration(result.modelLatencyMs());
        if (!result.success()) {
            recordCall("fallback");
            recordFallback(result.reason());
            log.warn("configured rerank 调用失败，回退 Java 启发式: projectId={} profileId={} providerId={} reason={}",
                    projectId, configuration.profile().id(), provider.id(), result.reason());
            return safeFallback(query, candidates, topN, result.reason());
        }

        List<RetrievalCandidate> scored = score(target, result.scores());
        scored.sort(candidateOrder());
        if (candidates.size() > processed) {
            scored.addAll(candidates.subList(processed, candidates.size()));
        }
        List<RetrievalCandidate> finalResult = truncate(scored, topN);
        recordCall("success");
        recordTotalDuration(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos));
        log.info("configured rerank 完成: projectId={} profileId={} providerId={} candidates={} totalMs={} modelMs={}",
                projectId, configuration.profile().id(), provider.id(), result.candidateCount(),
                result.totalMs(), result.modelLatencyMs());
        return finalResult;
    }

    private List<RetrievalCandidate> score(List<RetrievalCandidate> targets, List<RerankScore> scores) {
        Map<Integer, Double> byIndex = new LinkedHashMap<>();
        for (RerankScore score : scores) {
            byIndex.put(score.index(), score.score());
        }
        List<RetrievalCandidate> scored = new ArrayList<>(targets.size());
        for (int index = 0; index < targets.size(); index++) {
            Double score = byIndex.get(index);
            if (score == null) {
                continue;
            }
            scored.add(targets.get(index)
                    .withRerank(score, NAME)
                    .withMetadata(Map.of("rerankSource", NAME)));
        }
        return scored;
    }

    private List<RetrievalCandidate> safeFallback(String query, List<RetrievalCandidate> candidates,
            int topN, String reason) {
        try {
            List<RetrievalCandidate> fallbackResult = fallback.rerank(query, candidates, topN);
            if (fallbackResult == null) {
                fallbackResult = candidates;
            }
            List<RetrievalCandidate> marked = new ArrayList<>(fallbackResult.size());
            for (RetrievalCandidate candidate : fallbackResult) {
                Map<String, Object> metadata = new LinkedHashMap<>();
                metadata.put("rerankerDegraded", true);
                metadata.put("rerankSource", "degraded");
                metadata.put("rerankFallbackReason", reason == null ? "unknown" : reason);
                marked.add(candidate.withMetadata(metadata));
            }
            return truncate(marked, topN);
        } catch (Throwable fallbackFailure) {
            log.warn("Java 启发式回退再次异常: type={}", fallbackFailure.getClass().getSimpleName());
            List<RetrievalCandidate> safe = new ArrayList<>(candidates);
            safe.sort(candidateOrder());
            return truncate(safe, topN);
        }
    }

    private static Comparator<RetrievalCandidate> candidateOrder() {
        return (left, right) -> {
            int byScore = Double.compare(nullSafe(right.rerankScore()), nullSafe(left.rerankScore()));
            if (byScore != 0) {
                return byScore;
            }
            double leftRrf = left.rrfScore() == null ? 0.0 : left.rrfScore();
            double rightRrf = right.rrfScore() == null ? 0.0 : right.rrfScore();
            if (Math.abs(leftRrf - rightRrf) > EPSILON) {
                return Double.compare(rightRrf, leftRrf);
            }
            return left.id().compareTo(right.id());
        };
    }

    private static double nullSafe(Double value) {
        return value == null ? Double.NEGATIVE_INFINITY : value;
    }

    private static List<RetrievalCandidate> truncate(List<RetrievalCandidate> candidates, int topN) {
        if (topN > 0 && candidates.size() > topN) {
            return new ArrayList<>(candidates.subList(0, topN));
        }
        return candidates;
    }

    private void recordCall(String outcome) {
        try {
            Counter.builder(METRIC_CALLS).tag("outcome", outcome).register(meterRegistry).increment();
        } catch (RuntimeException ignored) {
            // 指标不得改变业务结果
        }
    }

    private void recordFallback(String reason) {
        try {
            Counter.builder(METRIC_FALLBACK).tag("reason", safeTag(reason)).register(meterRegistry).increment();
        } catch (RuntimeException ignored) {
            // 指标不得改变业务结果
        }
    }

    private void recordModelDuration(Integer modelLatencyMs) {
        if (modelLatencyMs == null) {
            return;
        }
        try {
            Timer.builder(METRIC_MODEL).publishPercentileHistogram().register(meterRegistry)
                    .record(Math.max(0, modelLatencyMs), TimeUnit.MILLISECONDS);
        } catch (RuntimeException ignored) {
            // 指标不得改变业务结果
        }
    }

    private void recordTotalDuration(long totalMs) {
        try {
            Timer.builder(METRIC_TOTAL).publishPercentileHistogram().register(meterRegistry)
                    .record(Math.max(0L, totalMs), TimeUnit.MILLISECONDS);
        } catch (RuntimeException ignored) {
            // 指标不得改变业务结果
        }
    }

    private static String safeTag(String reason) {
        if (reason == null || reason.isBlank()) {
            return "unknown";
        }
        return reason.length() > 64 ? reason.substring(0, 64) : reason;
    }

    private final class ProjectScopedReranker implements Reranker {
        private final UUID projectId;

        private ProjectScopedReranker(UUID projectId) {
            this.projectId = projectId;
        }

        @Override
        public String name() {
            return NAME;
        }

        @Override
        public List<RetrievalCandidate> rerank(String query, List<RetrievalCandidate> candidates, int topN) {
            return rerankScoped(projectId, query, candidates, topN);
        }
    }
}
