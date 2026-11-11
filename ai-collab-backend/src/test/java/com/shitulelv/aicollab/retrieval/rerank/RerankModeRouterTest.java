package com.shitulelv.aicollab.retrieval.rerank;

import com.shitulelv.aicollab.retrieval.pipeline.Reranker;
import com.shitulelv.aicollab.retrieval.pipeline.RetrievalCandidate;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * W3 rerank 模式路由测试：java 零外呼、shadow 非阻塞只读、python 成功/部分覆盖/各类失败回退，
 * 以及"证据不可变"断言。真实 HTTP 行为由 {@link RerankGatewayClientTest} 覆盖。
 */
class RerankModeRouterTest {

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
    void javaModeDelegatesWithoutAnyPythonCallButRecordsBaseline() {
        RerankModeRouter router = new RerankModeRouter(properties(RerankMode.JAVA, 3000, 8), gatewayClient, registry);
        Reranker wrapped = router.wrap(new FixedReranker("fixed", false), PROJECT_ID);

        List<RetrievalCandidate> result = wrapped.rerank("混合架构 向量检索", candidates(), 10);

        assertThat(ids(result)).containsExactly(C1, C2, C3);
        assertThat(result).allSatisfy(candidate -> assertThat(candidate.rerankerName()).isEqualTo("fixed"));
        verifyNoInteractions(gatewayClient);
        assertThat(registry.get("rerank.calls").tag("mode", "java").tag("outcome", "success")
                .counter().count()).isEqualTo(1.0);
        assertThat(registry.get("rerank.latency").tag("mode", "java").timer().count()).isEqualTo(1);
        assertThat(wrapped.name()).isEqualTo("fixed");
    }

    @Test
    void pythonModeReranksByPythonScoresAndKeepsEvidence() {
        when(gatewayClient.rerank(any())).thenReturn(response(Map.of(C1, 0.10, C2, 0.90, C3, 0.50)));
        RerankModeRouter router = new RerankModeRouter(properties(RerankMode.PYTHON, 3000, 8), gatewayClient, registry);
        Reranker wrapped = router.wrap(new FixedReranker("fixed", false), PROJECT_ID);

        List<RetrievalCandidate> result = wrapped.rerank("混合架构", candidates(), 10);

        assertThat(ids(result)).containsExactly(C2, C3, C1);
        assertThat(result).allSatisfy(candidate -> {
            assertThat(candidate.rerankerName()).isEqualTo("python-rerank");
            assertEvidenceUnchanged(candidate, original(candidate.id()));
        });
        assertThat(registry.get("rerank.calls").tag("mode", "python").tag("outcome", "success")
                .counter().count()).isEqualTo(1.0);
        assertThat(registry.get("rerank.latency").tag("mode", "python").timer().count()).isEqualTo(1);
        assertThat(partialScoreCount()).isZero();
    }

    @Test
    void pythonModeFallsBackOnDeadlineExceeded() {
        when(gatewayClient.rerank(any())).thenThrow(
                new RerankGatewayException(RerankGatewayErrorCode.DEADLINE_EXCEEDED, false, "timeout"));
        RerankModeRouter router = new RerankModeRouter(properties(RerankMode.PYTHON, 3000, 8), gatewayClient, registry);
        Reranker wrapped = router.wrap(new FixedReranker("fixed", false), PROJECT_ID);

        List<RetrievalCandidate> result = assertDoesNotThrow(
                () -> wrapped.rerank("混合架构", candidates(), 10));

        assertThat(ids(result)).containsExactly(C1, C2, C3);
        assertThat(result).allSatisfy(candidate -> {
            assertThat(candidate.metadata()).containsEntry("rerankerDegraded", true);
            assertThat(candidate.metadata()).containsEntry("rerankSource", "degraded");
            assertEvidenceUnchanged(candidate, original(candidate.id()));
        });
        // 回退后返回的仍是 delegate（启发式）的逐条分数
        assertThat(result).extracting(RetrievalCandidate::rerankScore)
                .containsExactly(0.9, 0.5, 0.1);
        assertThat(fallbackCount("timeout")).isEqualTo(1.0);
        assertThat(registry.get("rerank.calls").tag("mode", "python").tag("outcome", "fallback")
                .counter().count()).isEqualTo(1.0);
    }

    @Test
    void pythonModeFallsBackOnUpstream5xx() {
        when(gatewayClient.rerank(any())).thenThrow(
                new RerankGatewayException(RerankGatewayErrorCode.MODEL_UNAVAILABLE, true, "unavailable"));
        RerankModeRouter router = new RerankModeRouter(properties(RerankMode.PYTHON, 3000, 8), gatewayClient, registry);

        List<RetrievalCandidate> result = router.wrap(new FixedReranker("fixed", false), PROJECT_ID)
                .rerank("混合架构", candidates(), 10);

        assertThat(result).hasSize(3);
        assertThat(fallbackCount("unavailable")).isEqualTo(1.0);
    }

    @Test
    void pythonModeFallsBackOnInvalidJson() {
        when(gatewayClient.rerank(any())).thenThrow(
                new RerankGatewayException(RerankGatewayErrorCode.SCHEMA_VIOLATION, false, "invalid json"));
        RerankModeRouter router = new RerankModeRouter(properties(RerankMode.PYTHON, 3000, 8), gatewayClient, registry);

        List<RetrievalCandidate> result = router.wrap(new FixedReranker("fixed", false), PROJECT_ID)
                .rerank("混合架构", candidates(), 10);

        assertThat(result).hasSize(3);
        assertThat(fallbackCount("schema")).isEqualTo(1.0);
    }

    @Test
    void pythonModePartiallyScoresUsingDelegateHeuristic() {
        // worker 只返回 C2 的分数：C1/C3 必须用 delegate 启发式补齐且保留候选。
        when(gatewayClient.rerank(any())).thenReturn(response(Map.of(C2, 0.95)));
        RerankModeRouter router = new RerankModeRouter(properties(RerankMode.PYTHON, 3000, 8), gatewayClient, registry);

        List<RetrievalCandidate> result = router.wrap(new FixedReranker("fixed", false), PROJECT_ID)
                .rerank("混合架构", candidates(), 10);

        assertThat(result).hasSize(3);
        assertThat(result).allSatisfy(candidate -> {
            assertThat(candidate.metadata()).containsEntry("rerankPartialScore", true);
            assertEvidenceUnchanged(candidate, original(candidate.id()));
        });
        assertThat(scoreOf(result, C1)).isEqualTo(0.9);   // FixedReranker 用 rrfScore 作为启发式分
        assertThat(scoreOf(result, C2)).isEqualTo(0.95);  // Python 分优先
        assertThat(scoreOf(result, C3)).isEqualTo(0.1);
        assertThat(partialScoreCount()).isEqualTo(1.0);
    }

    @Test
    void pythonModeKeepsCandidatesMissingFromDelegateResult() {
        // delegate 在"取全量"时丢了 C3：C3 必须仍被保留（分数退到 rrf），不能丢候选。
        when(gatewayClient.rerank(any())).thenReturn(response(Map.of(C1, 0.9, C2, 0.5)));
        RerankModeRouter router = new RerankModeRouter(properties(RerankMode.PYTHON, 3000, 8), gatewayClient, registry);

        List<RetrievalCandidate> result = router.wrap(new FixedReranker("dropping", true), PROJECT_ID)
                .rerank("混合架构", candidates(), 10);

        assertThat(ids(result)).contains(C1, C2, C3);
        assertThat(scoreOf(result, C3)).isEqualTo(0.1);
        assertThat(partialScoreCount()).isEqualTo(1.0);
    }

    @Test
    void pythonPayloadNormalizesNullTitleAndBoundsText() {
        RetrievalCandidate noHeading = new RetrievalCandidate(C1, DOC_ID, "arch.md", null,
                "长".repeat(2500), "hash-1", Map.of(), 1, 0.9, null, null, 0.9, null, null);
        when(gatewayClient.rerank(any())).thenReturn(response(Map.of(C1, 0.5)));
        RerankModeRouter router = new RerankModeRouter(properties(RerankMode.PYTHON, 3000, 8), gatewayClient, registry);

        router.wrap(new FixedReranker("fixed", false), PROJECT_ID)
                .rerank("混合架构", List.of(noHeading), 10);

        ArgumentCaptor<RerankGatewayRequest> captor = ArgumentCaptor.forClass(RerankGatewayRequest.class);
        verify(gatewayClient).rerank(captor.capture());
        RerankGatewayRequest.Candidate candidate = captor.getValue().candidates().getFirst();
        assertThat(candidate.title()).isEmpty();
        assertThat(candidate.text()).hasSize(2000);
        assertThat(captor.getValue().projectId()).isEqualTo(PROJECT_ID);
    }

    @Test
    void pythonModeDoesNotCallPythonForBlankQuery() {
        RerankModeRouter router = new RerankModeRouter(properties(RerankMode.PYTHON, 3000, 8), gatewayClient, registry);

        List<RetrievalCandidate> result = router.wrap(new FixedReranker("fixed", false), PROJECT_ID)
                .rerank("  ", candidates(), 10);

        assertThat(result).hasSize(3);
        verify(gatewayClient, never()).rerank(any());
    }

    @Test
    void shadowReturnsJavaResultImmediatelyAndComparesAsynchronously() throws InterruptedException {
        when(gatewayClient.rerank(any())).thenAnswer(invocation -> {
            Thread.sleep(300);
            return response(Map.of(C1, 0.9, C2, 0.5, C3, 0.1));
        });
        RerankModeRouter router = new RerankModeRouter(properties(RerankMode.SHADOW, 3000, 2), gatewayClient, registry);
        Reranker wrapped = router.wrap(new FixedReranker("fixed", false), PROJECT_ID);

        long startNanos = System.nanoTime();
        List<RetrievalCandidate> result = wrapped.rerank("混合架构", candidates(), 10);
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;

        // 非阻塞：影子调用还在睡 300ms，用户路径必须已经返回。
        assertThat(elapsedMs).isLessThan(200);
        assertThat(ids(result)).containsExactly(C1, C2, C3);
        assertThat(result).allSatisfy(candidate -> {
            assertThat(candidate.rerankerName()).isEqualTo("fixed");
            assertThat(candidate.metadata()).doesNotContainKey("rerankerDegraded");
            assertEvidenceUnchanged(candidate, original(candidate.id()));
        });
        assertThat(wrapped.name()).isEqualTo("fixed");

        awaitUntil(() -> registry.get("rerank.shadow.topk_overlap").summary().count() == 1, 3000);
        assertThat(registry.get("rerank.shadow.topk_overlap").summary().totalAmount()).isEqualTo(1.0);
        assertThat(registry.get("rerank.shadow.score_delta").summary().totalAmount()).isEqualTo(0.0);
        assertThat(registry.get("rerank.calls").tag("mode", "shadow").tag("outcome", "success")
                .counter().count()).isEqualTo(1.0);
        assertThat(registry.get("rerank.shadow.latency").timer().count()).isEqualTo(1);
    }

    @Test
    void shadowFailureOnlyRecordsMetricAndKeepsJavaResult() throws InterruptedException {
        when(gatewayClient.rerank(any())).thenThrow(
                new RerankGatewayException(RerankGatewayErrorCode.MODEL_UNAVAILABLE, true, "unavailable"));
        RerankModeRouter router = new RerankModeRouter(properties(RerankMode.SHADOW, 3000, 8), gatewayClient, registry);

        List<RetrievalCandidate> result = router.wrap(new FixedReranker("fixed", false), PROJECT_ID)
                .rerank("混合架构", candidates(), 10);

        assertThat(ids(result)).containsExactly(C1, C2, C3);
        assertThat(result).allSatisfy(candidate -> {
            assertThat(candidate.metadata()).doesNotContainKey("rerankerDegraded");
            assertEvidenceUnchanged(candidate, original(candidate.id()));
        });
        awaitUntil(() -> counterCount("rerank.shadow.failure", "reason", "unavailable") == 1.0, 3000);
        assertThat(registry.get("rerank.calls").tag("mode", "shadow").tag("outcome", "fallback")
                .counter().count()).isEqualTo(1.0);
    }

    @Test
    void shadowTimeoutIsRecordedAsFailureWithinDeadline() throws InterruptedException {
        when(gatewayClient.rerank(any())).thenAnswer(invocation -> {
            try {
                Thread.sleep(500);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new RerankGatewayException(RerankGatewayErrorCode.INTERNAL, false, "interrupted");
            }
            return response(Map.of(C1, 0.9));
        });
        RerankModeRouter router = new RerankModeRouter(properties(RerankMode.SHADOW, 50, 8), gatewayClient, registry);

        long startNanos = System.nanoTime();
        List<RetrievalCandidate> result = router.wrap(new FixedReranker("fixed", false), PROJECT_ID)
                .rerank("混合架构", candidates(), 10);
        assertThat((System.nanoTime() - startNanos) / 1_000_000L).isLessThan(200);
        assertThat(result).hasSize(3);
        awaitUntil(() -> counterCount("rerank.shadow.failure", "reason", "timeout") == 1.0, 3000);
    }

    @Test
    void modeParsingIsCaseInsensitiveAndRejectsUnknownValues() {
        assertThat(RerankMode.from("JaVa")).isEqualTo(RerankMode.JAVA);
        assertThat(RerankMode.from("SHADOW")).isEqualTo(RerankMode.SHADOW);
        assertThat(RerankMode.from(" python ")).isEqualTo(RerankMode.PYTHON);
        assertThatThrownBy(() -> RerankMode.from("neural"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("rerank.mode");
        assertThatThrownBy(() -> RerankMode.from(" "))
                .isInstanceOf(IllegalStateException.class);
    }

    // ---------- helpers ----------

    private RerankProperties properties(RerankMode mode, int deadlineMs, int shadowTopK) {
        return new RerankProperties(mode, "http://127.0.0.1:1", deadlineMs, Duration.ofSeconds(2),
                "", 20, shadowTopK, 1.0, "");
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

    private static RetrievalCandidate original(UUID id) {
        return candidates().stream().filter(candidate -> candidate.id().equals(id)).findFirst().orElseThrow();
    }

    private static void assertEvidenceUnchanged(RetrievalCandidate actual, RetrievalCandidate original) {
        assertThat(actual.id()).isEqualTo(original.id());
        assertThat(actual.documentId()).isEqualTo(original.documentId());
        assertThat(actual.originalFilename()).isEqualTo(original.originalFilename());
        assertThat(actual.heading()).isEqualTo(original.heading());
        assertThat(actual.content()).isEqualTo(original.content());
        assertThat(actual.contentHash()).isEqualTo(original.contentHash());
        assertThat(actual.denseRank()).isEqualTo(original.denseRank());
        assertThat(actual.denseSimilarity()).isEqualTo(original.denseSimilarity());
        assertThat(actual.rrfScore()).isEqualTo(original.rrfScore());
        original.metadata().forEach((key, value) -> assertThat(actual.metadata()).containsEntry(key, value));
    }

    private static List<UUID> ids(List<RetrievalCandidate> candidates) {
        return candidates.stream().map(RetrievalCandidate::id).toList();
    }

    private static double scoreOf(List<RetrievalCandidate> candidates, UUID id) {
        return candidates.stream().filter(candidate -> candidate.id().equals(id))
                .findFirst().orElseThrow().rerankScore();
    }

    private static RerankGatewayResponse response(Map<UUID, Double> scores) {
        Map<String, Double> byId = new LinkedHashMap<>();
        scores.forEach((id, score) -> byId.put(id.toString(), score));
        return new RerankGatewayResponse(1, byId, "rerank-default",
                RerankGatewayResponse.Usage.unknown(), null);
    }

    private double partialScoreCount() {
        Counter counter = registry.find("rerank.partial_score").counter();
        return counter == null ? 0.0 : counter.count();
    }

    private double fallbackCount(String reason) {
        return counterCount("rerank.fallback", "reason", reason);
    }

    private double counterCount(String name, String tagKey, String tagValue) {
        Counter counter = registry.find(name).tag(tagKey, tagValue).counter();
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

    private static <T> T assertDoesNotThrow(ThrowingSupplier<T> supplier) {
        try {
            return supplier.get();
        } catch (Throwable failure) {
            throw new AssertionError("不应抛异常", failure);
        }
    }

    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get() throws Throwable;
    }

    /** 固定启发式替身：rerankScore = rrfScore，降序 + id 升序；可选丢弃最后一条以验证"不丢候选"。 */
    private static final class FixedReranker implements Reranker {

        private final String name;
        private final boolean dropLast;

        private FixedReranker(String name, boolean dropLast) {
            this.name = name;
            this.dropLast = dropLast;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public List<RetrievalCandidate> rerank(String query, List<RetrievalCandidate> candidates, int topN) {
            List<RetrievalCandidate> scored = new ArrayList<>();
            for (int index = 0; index < candidates.size(); index++) {
                if (dropLast && index == candidates.size() - 1) {
                    continue;
                }
                RetrievalCandidate candidate = candidates.get(index);
                scored.add(candidate.withRerank(
                        candidate.rrfScore() != null ? candidate.rrfScore() : 0.0, name));
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
