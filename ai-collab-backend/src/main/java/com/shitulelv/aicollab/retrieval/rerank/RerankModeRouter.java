package com.shitulelv.aicollab.retrieval.rerank;

import com.shitulelv.aicollab.retrieval.pipeline.Reranker;
import com.shitulelv.aicollab.retrieval.pipeline.RetrievalCandidate;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 按 {@code rerank.mode} 包装检索管线选中的重排器（W3 冻结契约 §3）。
 *
 * <ul>
 *   <li>{@link RerankMode#JAVA}：薄包装，只记录 {@code rerank.calls{mode=java}} 与
 *       {@code rerank.latency{mode=java}}，完全委托 delegate，输出与未包装时逐分数/逐顺序
 *       等价，<b>不发起任何 Python 调用</b>；</li>
 *   <li>{@link RerankMode#SHADOW}：同步算出并返回 Java 结果后，把 Python 对比调用
 *       <b>提交到有界后台执行器</b>（虚拟线程 + {@code Semaphore(8)}），绝不让影子调用
 *       进入用户请求关键路径；并发额度用尽时记 {@code rerank.shadow.skipped} 并放弃本次影子；</li>
 *   <li>{@link RerankMode#PYTHON}：同步等待 worker（它就是用户路径），成功按分数重排；
 *       超时/5xx/非法响应/覆盖不合法一律回退 delegate 结果并标记降级，<b>请求绝不失败</b>。</li>
 * </ul>
 *
 * <p><b>证据不可变</b>：只调用 {@link RetrievalCandidate#withRerank} 与
 * {@link RetrievalCandidate#withMetadata}，从不改 id/documentId/heading/content/contentHash
 * 及已有 metadata 键。</p>
 */
@Component
public class RerankModeRouter {

    private static final Logger log = LoggerFactory.getLogger(RerankModeRouter.class);

    static final String PYTHON_RERANKER_NAME = "python-rerank";
    static final String METADATA_PARTIAL_SCORE = "rerankPartialScore";
    static final String METADATA_DEGRADED = "rerankerDegraded";
    static final String METADATA_SOURCE = "rerankSource";

    static final String METRIC_CALLS = "rerank.calls";
    static final String METRIC_LATENCY = "rerank.latency";
    static final String METRIC_FALLBACK = "rerank.fallback";
    static final String METRIC_PARTIAL_SCORE = "rerank.partial_score";
    static final String METRIC_SHADOW_OVERLAP = "rerank.shadow.topk_overlap";
    static final String METRIC_SHADOW_DELTA = "rerank.shadow.score_delta";
    static final String METRIC_SHADOW_FAILURE = "rerank.shadow.failure";
    static final String METRIC_SHADOW_SKIPPED = "rerank.shadow.skipped";
    static final String METRIC_SHADOW_LATENCY = "rerank.shadow.latency";
    static final String METRIC_TOTAL_DURATION = "rerank.total.duration";
    static final String METRIC_OVERHEAD = "rerank.overhead";
    static final String METRIC_MODEL_LATENCY_KNOWN = "rerank.model_latency_known";
    static final String METRIC_INLINE_REJECTED = "rerank.inline.rejected";

    private static final double EPSILON = 1e-9;
    private static final int MAX_PAYLOAD_CANDIDATES = 50;
    private static final int MAX_TEXT_CHARS = 2000;
    private static final int MAX_TITLE_CHARS = 500;
    private static final int SHADOW_MAX_CONCURRENCY = 8;
    private static final Set<String> OUTCOMES = Set.of("success", "partial", "fallback", "skipped");

    private final RerankProperties properties;
    private final RerankGatewayClient gatewayClient;
    private final MeterRegistry meterRegistry;

    /** 虚拟线程执行器：影子任务不占平台线程；并发上限由信号量控制。 */
    private final ExecutorService shadowExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private final Semaphore shadowPermits = new Semaphore(SHADOW_MAX_CONCURRENCY);

    private final Timer javaTimer;
    private final Timer pythonTimer;
    private final Timer shadowTimer;
    /** {@code rerank.total.duration{mode,outcome}}：构造期全量预注册，保证看板基线稳定。 */
    private final Map<String, Timer> totalDurationTimers;
    private final Counter partialScoreCounter;
    private final DistributionSummary shadowOverlap;
    private final DistributionSummary shadowScoreDelta;

    public RerankModeRouter(RerankProperties properties, RerankGatewayClient gatewayClient,
            MeterRegistry meterRegistry) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.gatewayClient = Objects.requireNonNull(gatewayClient, "gatewayClient");
        this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry");

        this.javaTimer = Timer.builder(METRIC_LATENCY)
                .tag("mode", RerankMode.JAVA.tag())
                .publishPercentileHistogram()
                .register(meterRegistry);
        this.pythonTimer = Timer.builder(METRIC_LATENCY)
                .tag("mode", RerankMode.PYTHON.tag())
                .publishPercentileHistogram()
                .register(meterRegistry);
        this.shadowTimer = Timer.builder(METRIC_SHADOW_LATENCY)
                .publishPercentileHistogram()
                .register(meterRegistry);
        this.partialScoreCounter = Counter.builder(METRIC_PARTIAL_SCORE).register(meterRegistry);
        this.shadowOverlap = DistributionSummary.builder(METRIC_SHADOW_OVERLAP).register(meterRegistry);
        this.shadowScoreDelta = DistributionSummary.builder(METRIC_SHADOW_DELTA).register(meterRegistry);
        this.totalDurationTimers = registerTotalDurationTimers(meterRegistry);

        // 预注册 0 值计数器：保证 dashboards 在模式尚未发生调用时也有稳定基线。
        for (RerankMode mode : RerankMode.values()) {
            for (String outcome : OUTCOMES) {
                Counter.builder(METRIC_CALLS)
                        .tag("mode", mode.tag())
                        .tag("outcome", outcome)
                        .register(meterRegistry);
            }
        }
    }

    private static Map<String, Timer> registerTotalDurationTimers(MeterRegistry registry) {
        Map<String, Timer> timers = new HashMap<>();
        for (RerankMode mode : RerankMode.values()) {
            for (String outcome : OUTCOMES) {
                Timer timer = Timer.builder(METRIC_TOTAL_DURATION)
                        .tag("mode", mode.tag())
                        .tag("outcome", outcome)
                        .publishPercentileHistogram()
                        .register(registry);
                timers.put(totalKey(mode, outcome), timer);
            }
        }
        return Map.copyOf(timers);
    }

    private static String totalKey(RerankMode mode, String outcome) {
        return mode.tag() + "|" + outcome;
    }

    /**
     * 包装 active reranker。
     *
     * @param delegate 当前检索管线选中的重排器（非空）
     */
    public Reranker wrap(Reranker delegate) {
        return wrap(delegate, null);
    }

    /**
     * 包装 active reranker，并携带项目作用域（用于 Python 请求的幂等键与 callId）。
     */
    public Reranker wrap(Reranker delegate, UUID projectId) {
        Objects.requireNonNull(delegate, "delegate");
        // java 模式也套一层薄包装：只记调用数与可见延迟，不发起任何外呼、
        // 不改 name、不做候选拷贝，输出与原 delegate 逐分数/逐顺序等价。
        return new ModeReranker(delegate, properties.mode(), projectId);
    }

    @PreDestroy
    void shutdown() {
        shadowExecutor.shutdownNow();
    }

    private final class ModeReranker implements Reranker {

        private final Reranker delegate;
        private final RerankMode mode;
        private final UUID projectId;

        private ModeReranker(Reranker delegate, RerankMode mode, UUID projectId) {
            this.delegate = delegate;
            this.mode = mode;
            this.projectId = projectId;
        }

        @Override
        public String name() {
            return mode == RerankMode.PYTHON ? PYTHON_RERANKER_NAME : delegate.name();
        }

        @Override
        public List<RetrievalCandidate> rerank(String query, List<RetrievalCandidate> candidates, int topN) {
            if (mode == RerankMode.JAVA) {
                return rerankJava(delegate, query, candidates, topN);
            }
            if (candidates == null || candidates.isEmpty()) {
                long startNanos = System.nanoTime();
                try {
                    return delegate.rerank(query, candidates, topN);
                } finally {
                    long elapsedNanos = System.nanoTime() - startNanos;
                    recordCall(mode, "success");
                    recordTotalDuration(mode, "success", elapsedNanos);
                }
            }
            if (query == null || query.isBlank()) {
                long startNanos = System.nanoTime();
                try {
                    return delegate.rerank(query, candidates, topN);
                } finally {
                    long elapsedNanos = System.nanoTime() - startNanos;
                    recordCall(mode, "success");
                    recordTotalDuration(mode, "success", elapsedNanos);
                }
            }
            return mode == RerankMode.SHADOW
                    ? rerankShadow(delegate, projectId, query, candidates, topN)
                    : rerankPython(delegate, projectId, query, candidates, topN);
        }
    }

    /**
     * java 模式：只做计数与计时，完全委托 delegate。
     *
     * <p>刻意直接返回 delegate 的结果对象（不做拷贝/重排/加 metadata），保证与未包装时
     * 逐分数、逐顺序等价；也不产生任何 Python 调用。</p>
     */
    private List<RetrievalCandidate> rerankJava(Reranker delegate, String query,
            List<RetrievalCandidate> candidates, int topN) {
        long startNanos = System.nanoTime();
        String outcome = "success";
        try {
            return delegate.rerank(query, candidates, topN);
        } catch (RuntimeException | Error failure) {
            outcome = "fallback";
            throw failure;
        } finally {
            long elapsedNanos = System.nanoTime() - startNanos;
            recordCall(RerankMode.JAVA, outcome);
            javaTimer.record(elapsedNanos, TimeUnit.NANOSECONDS);
            recordTotalDuration(RerankMode.JAVA, outcome, elapsedNanos);
        }
    }

    private List<RetrievalCandidate> rerankPython(Reranker delegate, UUID projectId,
            String query, List<RetrievalCandidate> candidates, int topN) {
        long startNanos = System.nanoTime();
        String outcome = "success";
        RerankGatewayRequest request = null;
        RerankGatewayResponse response = null;
        try {
            if (!RerankExecutionPolicy.inlineAllowed(candidates.size())) {
                outcome = "fallback";
                recordInlineRejected("candidate_limit");
                recordFallback(RerankExecutionPolicy.INLINE_CANDIDATE_LIMIT_REASON);
                log.warn("inline rerank 拒绝超过 8 候选，回退 Java 启发式: candidates={}",
                        candidates.size());
                return degraded(delegate, query, candidates, topN);
            }
            List<RerankGatewayRequest.Candidate> payload = buildPayload(candidates);
            if (payload.isEmpty()) {
                // 没有可送 worker 的合法文本：不发起调用，全部按启发式补分并标 PARTIAL_SCORE。
                outcome = "partial";
                partialScoreCounter.increment();
                return scoreWithHeuristic(delegate, query, candidates, topN);
            }
            request = new RerankGatewayRequest(projectId, query,
                    requestTopK(topN, candidates.size()), payload,
                    RerankExecutionPolicy.inlineDeadlineMs(properties.deadlineMs()));
            response = gatewayClient.rerank(request);

            boolean partial = payload.size() < candidates.size()
                    || response.scores().size() < payload.size();
            Map<UUID, Double> heuristic = partial ? heuristicScores(delegate, query, candidates) : Map.of();
            List<RetrievalCandidate> scored = new ArrayList<>(candidates.size());
            for (RetrievalCandidate candidate : candidates) {
                Double score = response.scores().get(candidate.id().toString());
                if (score == null) {
                    score = heuristicScore(candidate, heuristic);
                }
                scored.add(candidate.withRerank(score, PYTHON_RERANKER_NAME));
            }
            if (partial) {
                for (int index = 0; index < scored.size(); index++) {
                    scored.set(index, scored.get(index).withMetadata(Map.of(METADATA_PARTIAL_SCORE, true)));
                }
                partialScoreCounter.increment();
                outcome = "partial";
            }
            scored.sort(candidateOrder());
            return truncate(scored, topN);
        } catch (RerankGatewayException failure) {
            outcome = "fallback";
            recordFallback(reasonOf(failure.code()));
            log.warn("python rerank 失败，回退 Java 启发式: ids={} code={}", candidates.size(), failure.code());
            return degraded(delegate, query, candidates, topN);
        } catch (Throwable unexpected) {
            outcome = "fallback";
            recordFallback("unexpected");
            log.warn("python rerank 未预期异常，回退 Java 启发式: ids={} type={}",
                    candidates.size(), unexpected.getClass().getSimpleName());
            return degraded(delegate, query, candidates, topN);
        } finally {
            long elapsedNanos = System.nanoTime() - startNanos;
            recordCall(RerankMode.PYTHON, outcome);
            pythonTimer.record(elapsedNanos, TimeUnit.NANOSECONDS);
            recordTotalDuration(RerankMode.PYTHON, outcome, elapsedNanos);
            Integer modelLatencyMs = response == null ? null : response.modelLatencyMs();
            // 只有拿到合法 worker 响应才谈得上"模型耗时是否已知"；失败回退没有 T_model，
            // 不计入 known 比例（否则失败率会污染门禁前置指标）。
            boolean measured = response != null && !"fallback".equals(outcome);
            Long overheadMs = measured
                    ? recordOverhead(elapsedNanos, modelLatencyMs)
                    : null;
            logRerankCall(request, RerankMode.PYTHON, elapsedNanos, modelLatencyMs, overheadMs,
                    candidates.size(), outcome);
        }
    }

    private List<RetrievalCandidate> rerankShadow(Reranker delegate, UUID projectId,
            String query, List<RetrievalCandidate> candidates, int topN) {
        long startNanos = System.nanoTime();
        List<RetrievalCandidate> javaResult = safeDelegate(delegate, query, candidates, topN);
        String outcome = "success";
        try {
            // W3.3：抽样判定在 Java 结果算完之后、提交后台之前，不影响用户可见结果与延迟。
            if (!shouldSampleShadow()) {
                recordShadowSkipped("sample");
                recordCall(RerankMode.SHADOW, "skipped");
                outcome = "skipped";
                return javaResult;
            }
            if (!shadowPermits.tryAcquire()) {
                recordShadowSkipped("saturated");
                recordCall(RerankMode.SHADOW, "skipped");
                outcome = "skipped";
                return javaResult;
            }
            try {
                shadowExecutor.submit(() -> {
                    try {
                        runShadowComparison(projectId, query, candidates, javaResult, topN);
                    } finally {
                        shadowPermits.release();
                    }
                });
            } catch (RejectedExecutionException rejected) {
                shadowPermits.release();
                recordShadowSkipped("saturated");
                recordCall(RerankMode.SHADOW, "skipped");
                outcome = "skipped";
            }
        } finally {
            long elapsedNanos = System.nanoTime() - startNanos;
            // 影子模式用户可见延迟 = Java 结果计算耗时（Python 对比在后台）。
            recordVisibleLatency(RerankMode.SHADOW, elapsedNanos);
            recordTotalDuration(RerankMode.SHADOW, outcome, elapsedNanos);
        }
        return javaResult;
    }

    /**
     * W3.3 影子采样：每次机会独立均匀抽样；{@code >=1} 全采、{@code <=0} 全不采。
     * 用 {@link ThreadLocalRandom} 避免共享 {@code Random} 的 CAS 争用。
     */
    private boolean shouldSampleShadow() {
        double rate = properties.shadowSampleRate();
        if (rate >= 1.0) {
            return true;
        }
        if (rate <= 0.0) {
            return false;
        }
        return ThreadLocalRandom.current().nextDouble() < rate;
    }

    private List<RetrievalCandidate> safeDelegate(Reranker delegate, String query,
            List<RetrievalCandidate> candidates, int topN) {
        try {
            List<RetrievalCandidate> result = delegate.rerank(query, candidates, topN);
            return result == null ? List.of() : result;
        } catch (Throwable failure) {
            log.warn("shadow 模式 Java 重排异常，按截断候选返回: name={} type={}",
                    delegate.name(), failure.getClass().getSimpleName());
            return truncate(new ArrayList<>(candidates), topN);
        }
    }

    private void runShadowComparison(UUID projectId, String query, List<RetrievalCandidate> candidates,
            List<RetrievalCandidate> javaResult, int topN) {
        long startNanos = System.nanoTime();
        String outcome = "success";
        RerankGatewayRequest request = null;
        RerankGatewayResponse response = null;
        try {
            List<RerankGatewayRequest.Candidate> payload = buildPayload(candidates);
            if (payload.isEmpty()) {
                recordCall(RerankMode.SHADOW, "success");
                return;
            }
            request = new RerankGatewayRequest(projectId, query,
                    requestTopK(topN, candidates.size()), payload, properties.deadlineMs());
            RerankGatewayRequest shadowRequest = request;
            Future<RerankGatewayResponse> call = shadowExecutor.submit(() -> gatewayClient.rerank(shadowRequest));
            try {
                response = call.get(properties.deadlineMs(), TimeUnit.MILLISECONDS);
                compareShadow(javaResult, candidates, response);
                recordCall(RerankMode.SHADOW, "success");
            } catch (TimeoutException timeout) {
                call.cancel(true);
                outcome = "fallback";
                recordShadowFailure("timeout");
                recordCall(RerankMode.SHADOW, "fallback");
                log.warn("rerank shadow 超时丢弃: ids={} deadlineMs={}", candidates.size(), properties.deadlineMs());
            } catch (ExecutionException failure) {
                String reason = reasonOf(failure.getCause());
                outcome = "fallback";
                recordShadowFailure(reason);
                recordCall(RerankMode.SHADOW, "fallback");
                log.warn("rerank shadow 失败丢弃: ids={} code={}", candidates.size(), reason);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                call.cancel(true);
                outcome = "fallback";
                recordShadowFailure("interrupted");
                recordCall(RerankMode.SHADOW, "fallback");
            }
        } catch (Throwable unexpected) {
            outcome = "fallback";
            recordShadowFailure("unexpected");
            recordCall(RerankMode.SHADOW, "fallback");
            log.warn("rerank shadow 未预期异常: ids={} type={}", candidates.size(),
                    unexpected.getClass().getSimpleName());
        } finally {
            long elapsedNanos = System.nanoTime() - startNanos;
            shadowTimer.record(elapsedNanos, TimeUnit.NANOSECONDS);
            Integer modelLatencyMs = response == null ? null : response.modelLatencyMs();
            // shadow 的 overhead 只进日志，不进 rerank.overhead（那是 python 门禁口径）。
            Long overheadMs = response != null && !"fallback".equals(outcome)
                    ? overheadMillis(elapsedNanos, modelLatencyMs)
                    : null;
            logRerankCall(request, RerankMode.SHADOW, elapsedNanos, modelLatencyMs, overheadMs,
                    candidates.size(), outcome);
        }
    }

    /**
     * 记录 {@code rerank.overhead{mode=python,model_latency_known}}。
     *
     * <p>{@code model_latency_known=true} 的样本才是门禁样本；未知时按契约记录
     * {@code T_total_java} 本身但打 {@code known=false} 标签，不参与门禁。</p>
     *
     * @return 日志用的 overhead 毫秒（模型耗时未知时为 null，即 UNKNOWN）
     */
    private Long recordOverhead(long totalNanos, Integer modelLatencyMs) {
        try {
            if (modelLatencyMs == null) {
                recordModelLatencyKnown("false");
                overheadTimer(false).record(Math.max(0L, totalNanos), TimeUnit.NANOSECONDS);
                return null;
            }
            recordModelLatencyKnown("true");
            long overheadNanos = Math.max(0L, totalNanos - TimeUnit.MILLISECONDS.toNanos(modelLatencyMs));
            overheadTimer(true).record(overheadNanos, TimeUnit.NANOSECONDS);
            return TimeUnit.NANOSECONDS.toMillis(overheadNanos);
        } catch (RuntimeException ignored) {
            // 指标不得改变业务结果
            return null;
        }
    }

    private void recordModelLatencyKnown(String known) {
        Counter.builder(METRIC_MODEL_LATENCY_KNOWN)
                .tag("known", known)
                .register(meterRegistry)
                .increment();
    }

    private Timer overheadTimer(boolean known) {
        return Timer.builder(METRIC_OVERHEAD)
                .tag("mode", RerankMode.PYTHON.tag())
                .tag("model_latency_known", Boolean.toString(known))
                .publishPercentileHistogram()
                .register(meterRegistry);
    }

    /** 只计算并返回 overhead（毫秒）；模型耗时未知返回 null。 */
    private static Long overheadMillis(long totalNanos, Integer modelLatencyMs) {
        if (modelLatencyMs == null) {
            return null;
        }
        return TimeUnit.NANOSECONDS.toMillis(
                Math.max(0L, totalNanos - TimeUnit.MILLISECONDS.toNanos(modelLatencyMs)));
    }

    private void compareShadow(List<RetrievalCandidate> javaResult, List<RetrievalCandidate> candidates,
            RerankGatewayResponse response) {
        int comparable = Math.min(javaResult.size(), candidates.size());
        int topK = Math.min(properties.shadowTopK(), comparable);
        if (topK <= 0) {
            shadowOverlap.record(0.0);
            shadowScoreDelta.record(0.0);
            return;
        }
        Set<String> javaTopIds = new HashSet<>();
        for (RetrievalCandidate candidate : javaResult.subList(0, topK)) {
            javaTopIds.add(candidate.id().toString());
        }
        List<String> pythonTopIds = pythonTopIds(candidates, response.scores(), topK);
        int hit = 0;
        for (String id : pythonTopIds) {
            if (javaTopIds.contains(id)) {
                hit++;
            }
        }
        shadowOverlap.record((double) hit / topK);
        shadowScoreDelta.record(meanAbsoluteDelta(javaResult, response.scores()));
    }

    private static List<String> pythonTopIds(List<RetrievalCandidate> candidates,
            Map<String, Double> scores, int topK) {
        List<RetrievalCandidate> scored = new ArrayList<>();
        for (RetrievalCandidate candidate : candidates) {
            if (scores.containsKey(candidate.id().toString())) {
                scored.add(candidate);
            }
        }
        scored.sort((left, right) -> {
            double leftScore = scores.get(left.id().toString());
            double rightScore = scores.get(right.id().toString());
            int byScore = Double.compare(rightScore, leftScore);
            if (byScore != 0) {
                return byScore;
            }
            double leftRrf = left.rrfScore() != null ? left.rrfScore() : 0.0;
            double rightRrf = right.rrfScore() != null ? right.rrfScore() : 0.0;
            if (Math.abs(leftRrf - rightRrf) > EPSILON) {
                return Double.compare(rightRrf, leftRrf);
            }
            return left.id().compareTo(right.id());
        });
        List<String> ids = new ArrayList<>(Math.min(topK, scored.size()));
        for (RetrievalCandidate candidate : scored) {
            if (ids.size() == topK) {
                break;
            }
            ids.add(candidate.id().toString());
        }
        return ids;
    }

    private static double meanAbsoluteDelta(List<RetrievalCandidate> javaResult, Map<String, Double> pythonScores) {
        double sum = 0.0;
        int count = 0;
        for (RetrievalCandidate candidate : javaResult) {
            Double javaScore = candidate.rerankScore();
            Double pythonScore = pythonScores.get(candidate.id().toString());
            if (javaScore != null && pythonScore != null) {
                sum += Math.abs(javaScore - pythonScore);
                count++;
            }
        }
        return count == 0 ? 0.0 : sum / count;
    }

    private List<RerankGatewayRequest.Candidate> buildPayload(List<RetrievalCandidate> candidates) {
        int limit = Math.min(candidates.size(), properties.maxCandidates());
        List<RerankGatewayRequest.Candidate> payload = new ArrayList<>(limit);
        for (int index = 0; index < limit; index++) {
            RetrievalCandidate candidate = candidates.get(index);
            String text = truncateText(candidate.effectiveContent(), MAX_TEXT_CHARS);
            if (text == null || text.isBlank()) {
                continue;
            }
            String title = truncateText(candidate.heading(), MAX_TITLE_CHARS);
            payload.add(new RerankGatewayRequest.Candidate(
                    candidate.id().toString(),
                    title == null ? "" : title,
                    text));
        }
        return payload;
    }

    private List<RetrievalCandidate> scoreWithHeuristic(Reranker delegate, String query,
            List<RetrievalCandidate> candidates, int topN) {
        Map<UUID, Double> heuristic = heuristicScores(delegate, query, candidates);
        List<RetrievalCandidate> scored = new ArrayList<>(candidates.size());
        for (RetrievalCandidate candidate : candidates) {
            scored.add(candidate
                    .withRerank(heuristicScore(candidate, heuristic), PYTHON_RERANKER_NAME)
                    .withMetadata(Map.of(METADATA_PARTIAL_SCORE, true)));
        }
        scored.sort(candidateOrder());
        return truncate(scored, topN);
    }

    /** 用 delegate 的完整结果（topN=候选数）建 id→启发式分数索引；delegate 异常时为空索引。 */
    private Map<UUID, Double> heuristicScores(Reranker delegate, String query,
            List<RetrievalCandidate> candidates) {
        try {
            List<RetrievalCandidate> heuristic = delegate.rerank(query, candidates, candidates.size());
            if (heuristic == null) {
                return Map.of();
            }
            Map<UUID, Double> scores = new HashMap<>();
            for (RetrievalCandidate candidate : heuristic) {
                if (candidate.rerankScore() != null) {
                    scores.put(candidate.id(), candidate.rerankScore());
                }
            }
            return scores;
        } catch (Throwable failure) {
            log.warn("启发式补分失败，缺失 id 退回 rrf 分: type={}", failure.getClass().getSimpleName());
            return Map.of();
        }
    }

    private List<RetrievalCandidate> degraded(Reranker delegate, String query,
            List<RetrievalCandidate> candidates, int topN) {
        List<RetrievalCandidate> javaResult;
        try {
            List<RetrievalCandidate> result = delegate.rerank(query, candidates, topN);
            javaResult = result == null ? List.of() : new ArrayList<>(result);
        } catch (Throwable failure) {
            log.warn("Java 重排器在降级路径再次异常: type={}", failure.getClass().getSimpleName());
            javaResult = new ArrayList<>(candidates);
        }
        if (javaResult.isEmpty()) {
            javaResult = new ArrayList<>(candidates);
        }
        javaResult = truncate(javaResult, topN);
        List<RetrievalCandidate> marked = new ArrayList<>(javaResult.size());
        for (RetrievalCandidate candidate : javaResult) {
            marked.add(candidate.withMetadata(Map.of(
                    METADATA_DEGRADED, true,
                    METADATA_SOURCE, "degraded")));
        }
        return marked;
    }

    private static double heuristicScore(RetrievalCandidate candidate, Map<UUID, Double> heuristicScores) {
        Double score = heuristicScores.get(candidate.id());
        if (score != null) {
            return score;
        }
        return candidate.rrfScore() != null ? candidate.rrfScore() : 0.0;
    }

    private static Comparator<RetrievalCandidate> candidateOrder() {
        return (left, right) -> {
            double leftScore = left.rerankScore() != null ? left.rerankScore() : Double.NEGATIVE_INFINITY;
            double rightScore = right.rerankScore() != null ? right.rerankScore() : Double.NEGATIVE_INFINITY;
            int byScore = Double.compare(rightScore, leftScore);
            if (byScore != 0) {
                return byScore;
            }
            double leftRrf = left.rrfScore() != null ? left.rrfScore() : 0.0;
            double rightRrf = right.rrfScore() != null ? right.rrfScore() : 0.0;
            if (Math.abs(leftRrf - rightRrf) > EPSILON) {
                return Double.compare(rightRrf, leftRrf);
            }
            return left.id().compareTo(right.id());
        };
    }

    private static List<RetrievalCandidate> truncate(List<RetrievalCandidate> candidates, int topN) {
        if (topN > 0 && candidates.size() > topN) {
            return new ArrayList<>(candidates.subList(0, topN));
        }
        return candidates;
    }

    private static int requestTopK(int topN, int candidateCount) {
        int requested = topN > 0 ? topN : candidateCount;
        return Math.max(1, Math.min(requested, MAX_PAYLOAD_CANDIDATES));
    }

    private static String truncateText(String value, int maxChars) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.codePointCount(0, trimmed.length()) <= maxChars) {
            return trimmed;
        }
        int endIndex = trimmed.offsetByCodePoints(0, maxChars);
        return trimmed.substring(0, endIndex);
    }

    private void recordCall(RerankMode mode, String outcome) {
        Counter.builder(METRIC_CALLS)
                .tag("mode", mode.tag())
                .tag("outcome", outcome)
                .register(meterRegistry)
                .increment();
    }

    private void recordTotalDuration(RerankMode mode, String outcome, long elapsedNanos) {
        try {
            Timer timer = totalDurationTimers.get(totalKey(mode, outcome));
            if (timer != null) {
                timer.record(Math.max(0L, elapsedNanos), TimeUnit.NANOSECONDS);
            }
        } catch (RuntimeException ignored) {
            // 指标不得改变业务结果
        }
    }

    private void recordShadowSkipped(String reason) {
        try {
            Counter.builder(METRIC_SHADOW_SKIPPED)
                    .tag("reason", reason)
                    .register(meterRegistry)
                    .increment();
        } catch (RuntimeException ignored) {
            // 指标不得改变业务结果
        }
    }

    private void recordInlineRejected(String reason) {
        try {
            Counter.builder(METRIC_INLINE_REJECTED)
                    .tag("reason", reason)
                    .register(meterRegistry)
                    .increment();
        } catch (RuntimeException ignored) {
            // 指标不得改变业务结果
        }
    }

    /**
     * 每次 python/shadow 调用完成打印一行固定字段日志（W3.1 §1.3）。
     *
     * <p>只含 callId / 模式 / 三段耗时 / 候选条数 / 结果，不含 query、候选正文、Prompt、令牌。
     * java 模式不打印。python 用 INFO（用户关键路径，调用量 = 查询量）；shadow 用 DEBUG
     * （后台对比调用量更高，且只是观测）。</p>
     */
    private void logRerankCall(RerankGatewayRequest request, RerankMode mode, long totalNanos,
            Integer modelLatencyMs, Long overheadMs, int candidateCount, String outcome) {
        if (request == null) {
            return;
        }
        if (mode == RerankMode.SHADOW && !log.isDebugEnabled()) {
            return;
        }
        if (mode != RerankMode.SHADOW && !log.isInfoEnabled()) {
            return;
        }
        String callId;
        try {
            String scope = gatewayClient.callScope(request);
            callId = scope == null || scope.isBlank() ? "unknown:unknown:1" : scope + ":1";
        } catch (RuntimeException scopeFailure) {
            callId = "unknown:unknown:1";
        }
        String modelMs = modelLatencyMs == null ? "UNKNOWN" : Integer.toString(modelLatencyMs);
        String overhead = overheadMs == null ? "UNKNOWN" : Long.toString(overheadMs);
        String pattern = "call_id={} mode={} total_ms={} model_ms={} overhead_ms={} candidates={} outcome={}";
        Object[] arguments = {callId, mode.tag(), TimeUnit.NANOSECONDS.toMillis(Math.max(0L, totalNanos)),
                modelMs, overhead, candidateCount, outcome};
        if (mode == RerankMode.SHADOW) {
            log.debug(pattern, arguments);
        } else {
            log.info(pattern, arguments);
        }
    }

    private void recordFallback(String reason) {
        Counter.builder(METRIC_FALLBACK).tag("reason", reason).register(meterRegistry).increment();
    }

    private void recordShadowFailure(String reason) {
        Counter.builder(METRIC_SHADOW_FAILURE).tag("reason", reason).register(meterRegistry).increment();
    }

    private void recordVisibleLatency(RerankMode mode, long elapsedNanos) {
        Timer.builder(METRIC_LATENCY)
                .tag("mode", mode.tag())
                .publishPercentileHistogram()
                .register(meterRegistry)
                .record(elapsedNanos, TimeUnit.NANOSECONDS);
    }

    private static String reasonOf(RerankGatewayErrorCode code) {
        return switch (code) {
            case DEADLINE_EXCEEDED -> "timeout";
            case MODEL_UNAVAILABLE -> "unavailable";
            case MODEL_RATE_LIMITED -> "rate_limited";
            case SCHEMA_VIOLATION -> "schema";
            case INVALID_REQUEST -> "invalid_request";
            case INTERNAL -> "internal";
        };
    }

    private static String reasonOf(Throwable failure) {
        if (failure instanceof RerankGatewayException gatewayFailure) {
            return reasonOf(gatewayFailure.code());
        }
        return "unexpected";
    }
}
