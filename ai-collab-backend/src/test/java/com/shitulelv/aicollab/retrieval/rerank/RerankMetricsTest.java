package com.shitulelv.aicollab.retrieval.rerank;

import com.shitulelv.aicollab.retrieval.pipeline.Reranker;
import com.shitulelv.aicollab.retrieval.pipeline.RetrievalCandidate;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * W3.1/W3.3 仪表落点测试（冻结契约 §1.2 / §1.3 / §3）。
 *
 * <p>覆盖：{@code rerank.total.duration} 三条路径、python 成功/未知 modelLatency 的
 * {@code rerank.overhead} 与 {@code rerank.model_latency_known}、java 模式不产生 overhead
 * 样本、shadow 采样 0/1 与信号量饱和时的 {@code skipped{reason}}。真实 HTTP 与重试计数
 * 由 {@link RerankGatewayClientTest} 覆盖；门禁预算由 {@link RerankOverheadBudgetTest} 覆盖。</p>
 */
class RerankMetricsTest {

    private static final UUID PROJECT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID DOC_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID C1 = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID C2 = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final UUID C3 = UUID.fromString("55555555-5555-5555-5555-555555555555");

    private final RerankGatewayClient gatewayClient = mock(RerankGatewayClient.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    @BeforeEach
    void setUp() {
        registry.clear();
    }

    @Test
    void javaModeRecordsTotalDurationButNeverProducesOverheadSamples() {
        RerankModeRouter router = new RerankModeRouter(properties(RerankMode.JAVA, 1.0), gatewayClient, registry);

        router.wrap(new FixedReranker(), PROJECT_ID).rerank("混合架构", candidates(), 10);

        assertThat(timerCount("rerank.total.duration", "mode", "java", "outcome", "success")).isEqualTo(1);
        assertThat(registry.find("rerank.overhead").timer()).isNull();
        assertThat(registry.find("rerank.model_latency_known").counter()).isNull();
        verifyNoInteractions(gatewayClient);
    }

    @Test
    void pythonSuccessRecordsTotalOverheadAndKnownTrue() {
        when(gatewayClient.rerank(any())).thenReturn(response(scores(), 5));
        RerankModeRouter router = new RerankModeRouter(properties(RerankMode.PYTHON, 1.0), gatewayClient, registry);

        router.wrap(new FixedReranker(), PROJECT_ID).rerank("混合架构", candidates(), 10);

        assertThat(timerCount("rerank.total.duration", "mode", "python", "outcome", "success")).isEqualTo(1);
        Timer overhead = registry.get("rerank.overhead")
                .tag("mode", "python").tag("model_latency_known", "true").timer();
        assertThat(overhead.count()).isEqualTo(1);
        assertThat(overhead.totalTime(TimeUnit.MILLISECONDS)).isGreaterThanOrEqualTo(0.0);
        assertThat(counterCount("rerank.model_latency_known", "known", "true")).isEqualTo(1.0);
        assertThat(registry.find("rerank.overhead").tag("model_latency_known", "false").timer()).isNull();
        assertThat(registry.find("rerank.model_latency_known").tag("known", "false").counter()).isNull();
    }

    @Test
    void pythonResponseWithoutModelLatencyMarksKnownFalseAndProducesNoGateSample() {
        when(gatewayClient.rerank(any())).thenReturn(response(scores(), null));
        RerankModeRouter router = new RerankModeRouter(properties(RerankMode.PYTHON, 1.0), gatewayClient, registry);

        router.wrap(new FixedReranker(), PROJECT_ID).rerank("混合架构", candidates(), 10);

        assertThat(counterCount("rerank.model_latency_known", "known", "false")).isEqualTo(1.0);
        assertThat(timerCount("rerank.overhead", "mode", "python", "model_latency_known", "false"))
                .isEqualTo(1);
        // 门禁只看 model_latency_known=true：未知样本不得冒充门禁样本。
        assertThat(registry.find("rerank.overhead").tag("model_latency_known", "true").timer()).isNull();
        assertThat(timerCount("rerank.total.duration", "mode", "python", "outcome", "success")).isEqualTo(1);
    }

    @Test
    void pythonFallbackRecordsTotalDurationButNoOverheadOrKnownCounter() {
        when(gatewayClient.rerank(any())).thenThrow(
                new RerankGatewayException(RerankGatewayErrorCode.MODEL_UNAVAILABLE, true, "unavailable"));
        RerankModeRouter router = new RerankModeRouter(properties(RerankMode.PYTHON, 1.0), gatewayClient, registry);

        router.wrap(new FixedReranker(), PROJECT_ID).rerank("混合架构", candidates(), 10);

        assertThat(timerCount("rerank.total.duration", "mode", "python", "outcome", "fallback")).isEqualTo(1);
        assertThat(registry.find("rerank.overhead").timer()).isNull();
        assertThat(registry.find("rerank.model_latency_known").counter()).isNull();
        assertThat(counterCount("rerank.fallback", "reason", "unavailable")).isEqualTo(1.0);
    }

    @Test
    void overheadAndTotalTimersExposeHistogramBucketsOnPrometheusRegistry() {
        // SimpleMeterRegistry 不实现桶，用 PrometheusMeterRegistry 验证 publishPercentileHistogram
        // 真的产生了 le 桶（看板 histogram_quantile 依赖它）。
        PrometheusMeterRegistry prometheus = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        when(gatewayClient.rerank(any())).thenReturn(response(scores(), 5));
        RerankModeRouter router = new RerankModeRouter(properties(RerankMode.PYTHON, 1.0), gatewayClient, prometheus);
        try {
            router.wrap(new FixedReranker(), PROJECT_ID).rerank("混合架构", candidates(), 10);
            String scrape = prometheus.scrape();

            assertThat(scrape).contains(
                    "rerank_overhead_seconds_bucket{mode=\"python\",model_latency_known=\"true\"");
            assertThat(scrape).contains(
                    "rerank_overhead_seconds_count{mode=\"python\",model_latency_known=\"true\"} 1");
            assertThat(scrape).contains(
                    "rerank_total_duration_seconds_bucket{mode=\"python\",outcome=\"success\"");
            assertThat(scrape).contains(
                    "rerank_total_duration_seconds_count{mode=\"python\",outcome=\"success\"} 1");
            assertThat(scrape).contains(
                    "rerank_model_latency_known_total{known=\"true\"} 1");
        } finally {
            router.shutdown();
        }
    }

    @Test
    void shadowSampleRateZeroSkipsWithoutCallingWorker() {
        RerankModeRouter router = new RerankModeRouter(properties(RerankMode.SHADOW, 0.0), gatewayClient, registry);

        List<RetrievalCandidate> result = router.wrap(new FixedReranker(), PROJECT_ID)
                .rerank("混合架构", candidates(), 10);

        assertThat(result).hasSize(3);
        verifyNoInteractions(gatewayClient);
        assertThat(counterCount("rerank.shadow.skipped", "reason", "sample")).isEqualTo(1.0);
        assertThat(counterCount("rerank.calls", "mode", "shadow", "outcome", "skipped")).isEqualTo(1.0);
        assertThat(timerCount("rerank.total.duration", "mode", "shadow", "outcome", "skipped")).isEqualTo(1);
        // 用户可见延迟照旧记录（采样不改变用户路径）。
        assertThat(timerCount("rerank.latency", "mode", "shadow")).isEqualTo(1);
        assertThat(registry.find("rerank.shadow.skipped").tag("reason", "saturated").counter()).isNull();
    }

    @Test
    void shadowSampleRateOneStillCallsWorker() throws InterruptedException {
        when(gatewayClient.rerank(any())).thenReturn(response(scores(), null));
        RerankModeRouter router = new RerankModeRouter(properties(RerankMode.SHADOW, 1.0), gatewayClient, registry);

        router.wrap(new FixedReranker(), PROJECT_ID).rerank("混合架构", candidates(), 10);

        awaitUntil(() -> timerCount("rerank.shadow.latency") == 1, 3000);
        verify(gatewayClient).rerank(any());
        assertThat(counterCount("rerank.calls", "mode", "shadow", "outcome", "success")).isEqualTo(1.0);
        assertThat(registry.find("rerank.shadow.skipped").counter()).isNull();
        assertThat(timerCount("rerank.total.duration", "mode", "shadow", "outcome", "success")).isEqualTo(1);
    }

    @Test
    void shadowSaturationSkipsWithSaturatedReason() throws InterruptedException {
        CountDownLatch entered = new CountDownLatch(8);
        CountDownLatch release = new CountDownLatch(1);
        when(gatewayClient.rerank(any())).thenAnswer(invocation -> {
            entered.countDown();
            release.await(5, TimeUnit.SECONDS);
            return response(scores(), null);
        });
        RerankModeRouter router = new RerankModeRouter(properties(RerankMode.SHADOW, 1.0), gatewayClient, registry);
        Reranker wrapped = router.wrap(new FixedReranker(), PROJECT_ID);
        try {
            for (int index = 0; index < 8; index++) {
                wrapped.rerank("混合架构", candidates(), 10);
            }
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            // 第 9 次：8 个许可都被阻塞中的影子任务占着 → 直接跳过，用户路径不受影响。
            wrapped.rerank("混合架构", candidates(), 10);

            assertThat(counterCount("rerank.shadow.skipped", "reason", "saturated")).isEqualTo(1.0);
            assertThat(counterCount("rerank.calls", "mode", "shadow", "outcome", "skipped")).isEqualTo(1.0);
            assertThat(timerCount("rerank.total.duration", "mode", "shadow", "outcome", "skipped")).isEqualTo(1);
        } finally {
            release.countDown();
            router.shutdown();
        }
    }

    // ---------- helpers ----------

    private RerankProperties properties(RerankMode mode, double shadowSampleRate) {
        return new RerankProperties(mode, "http://127.0.0.1:1", 3000, Duration.ofSeconds(2),
                "", 20, 8, shadowSampleRate, "");
    }

    private static Map<UUID, Double> scores() {
        Map<UUID, Double> scores = new LinkedHashMap<>();
        scores.put(C1, 0.10);
        scores.put(C2, 0.90);
        scores.put(C3, 0.50);
        return scores;
    }

    private static RerankGatewayResponse response(Map<UUID, Double> scores, Integer modelLatencyMs) {
        Map<String, Double> byId = new LinkedHashMap<>();
        scores.forEach((id, score) -> byId.put(id.toString(), score));
        return new RerankGatewayResponse(1, byId, "rerank-default",
                RerankGatewayResponse.Usage.unknown(), modelLatencyMs);
    }

    private static List<RetrievalCandidate> candidates() {
        return List.of(
                candidate(C1, "混合架构", "第一句介绍向量检索。第二句介绍混合检索融合算法。", 0.9),
                candidate(C2, "数据库设计", "采用 PostgreSQL pgvector 存储高维向量。", 0.5),
                candidate(C3, "其他说明", "系统遵循不可变证据契约规范。", 0.1));
    }

    private static RetrievalCandidate candidate(UUID id, String heading, String content, double rrf) {
        return new RetrievalCandidate(id, DOC_ID, "arch.md", heading, content, "hash-" + id,
                Map.of("bodySnapshotId", "snap1", "charFrom", 0, "charThrough", 30),
                1, 0.88, null, null, rrf, null, null);
    }

    private double timerCount(String name, String... tags) {
        io.micrometer.core.instrument.search.Search search = registry.find(name);
        for (int index = 0; index < tags.length; index += 2) {
            search = search.tag(tags[index], tags[index + 1]);
        }
        Timer timer = search.timer();
        return timer == null ? 0.0 : timer.count();
    }

    private double counterCount(String name, String... tags) {
        io.micrometer.core.instrument.search.Search search = registry.find(name);
        for (int index = 0; index < tags.length; index += 2) {
            search = search.tag(tags[index], tags[index + 1]);
        }
        Counter counter = search.counter();
        return counter == null ? 0.0 : counter.count();
    }

    private static void awaitUntil(BooleanSupplier condition, long timeoutMs) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(10);
        }
        fail("条件在 " + timeoutMs + "ms 内未满足");
    }

    /** 固定启发式替身：rerankScore = rrfScore，降序 + id 升序。 */
    private static final class FixedReranker implements Reranker {

        @Override
        public String name() {
            return "fixed";
        }

        @Override
        public List<RetrievalCandidate> rerank(String query, List<RetrievalCandidate> candidates, int topN) {
            List<RetrievalCandidate> scored = new ArrayList<>();
            for (RetrievalCandidate candidate : candidates) {
                scored.add(candidate.withRerank(
                        candidate.rrfScore() != null ? candidate.rrfScore() : 0.0, name()));
            }
            scored.sort(Comparator
                    .comparingDouble((RetrievalCandidate candidate) -> candidate.rerankScore()).reversed()
                    .thenComparing(candidate -> candidate.id().toString()));
            if (topN > 0 && scored.size() > topN) {
                return new ArrayList<>(scored.subList(0, topN));
            }
            return scored;
        }
    }
}
