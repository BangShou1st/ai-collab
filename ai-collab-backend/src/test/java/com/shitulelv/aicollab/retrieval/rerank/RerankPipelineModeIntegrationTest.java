package com.shitulelv.aicollab.retrieval.rerank;

import com.shitulelv.aicollab.retrieval.pipeline.DenseRetriever;
import com.shitulelv.aicollab.retrieval.pipeline.LexicalRetriever;
import com.shitulelv.aicollab.retrieval.pipeline.ParentChildExpander;
import com.shitulelv.aicollab.retrieval.pipeline.ReciprocalRankFusion;
import com.shitulelv.aicollab.retrieval.pipeline.RerankerType;
import com.shitulelv.aicollab.retrieval.pipeline.RetrievalCandidate;
import com.shitulelv.aicollab.retrieval.pipeline.RetrievalPipeline;
import com.shitulelv.aicollab.retrieval.pipeline.RetrievalPipeline.PipelineConfig;
import com.shitulelv.aicollab.retrieval.pipeline.RetrievalResult;
import com.shitulelv.aicollab.retrieval.pipeline.SemanticHeuristicReranker;
import com.shitulelv.aicollab.retrieval.pipeline.SentenceWindowExpander;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 管线级 {@code rerank.mode} 行为验收（冻结契约 §3，含 shadow 非阻塞与 java 薄包装两条修正）。
 *
 * <p>与 {@link RerankModeRouterTest} 的分工：那个类只测包装器本身（直接调用
 * {@code router.wrap(...).rerank(...)}），本类用<b>真实 {@link RetrievalPipeline}</b>
 * 走完 Dense → Lexical → RRF → 重排 → Trace 全链路，验证模式开关接入管线后的可观测行为：</p>
 *
 * <ol>
 *   <li>java：与 6 参构造器（无 router 的既有装配路径）输出逐字段一致，且对 Python 网关零调用；</li>
 *   <li>shadow：返回 Java 结果，用户线程不等待 Python（网关阻塞时仍在 200ms 内返回）；</li>
 *   <li>python：全量分数按 Python 分数排序，{@code rerankerName=python-rerank}，证据字段不变；</li>
 *   <li>python + {@code MODEL_UNAVAILABLE}：回退启发式、标 degraded、请求不失败；</li>
 *   <li>{@code PASS_THROUGH}（dense-only）：即便 {@code mode=python} 也不外呼、语义不被改写。</li>
 * </ol>
 *
 * <p>断言只比较 retrieval 侧可见值，不断言 {@code RetrievalTrace} 的毫秒字段（天然抖动）。</p>
 */
class RerankPipelineModeIntegrationTest {

    private static final UUID PROJECT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID DOC_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID C1 = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID C2 = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final UUID C3 = UUID.fromString("55555555-5555-5555-5555-555555555555");
    private static final UUID C4 = UUID.fromString("66666666-6666-6666-6666-666666666666");

    private static final String QUERY = "混合架构 向量检索";
    private static final String DOC_NAME = "architecture.md";

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final List<RerankModeRouter> routers = new ArrayList<>();

    private RerankGatewayClient gateway;
    private DenseRetriever denseRetriever;
    private LexicalRetriever lexicalRetriever;

    @BeforeEach
    void setUp() {
        registry.clear();
        gateway = mock(RerankGatewayClient.class);
        denseRetriever = mock(DenseRetriever.class);
        lexicalRetriever = mock(LexicalRetriever.class);
        when(denseRetriever.retrieve(any(), any(), any(), anyInt())).thenReturn(denseHits());
        when(lexicalRetriever.retrieve(any(), any(), any(), anyInt())).thenReturn(lexicalHits());
    }

    @AfterEach
    void tearDown() {
        routers.forEach(RerankModeRouter::shutdown);
        routers.clear();
    }

    @Test
    void javaModeProducesSameOutputAsUnwrappedPipelineAndNeverCallsPython() {
        RetrievalResult baseline = pipelineWithoutRouter().execute(PROJECT_ID, QUERY, List.of(DOC_ID), config());
        RetrievalResult wrapped = pipelineWithRouter(RerankMode.JAVA)
                .execute(PROJECT_ID, QUERY, List.of(DOC_ID), config());

        // java 模式是薄包装：id 顺序、每个候选的 rerankScore/rerankerName/metadata 必须逐字段相同。
        assertSameCandidates(baseline.candidates(), wrapped.candidates());
        assertThat(wrapped.trace().rerankerName()).isEqualTo(baseline.trace().rerankerName());
        assertThat(wrapped.trace().rerankerName()).isEqualTo(SemanticHeuristicReranker.NAME);
        assertThat(wrapped.trace().rerankerDegraded()).isFalse();
        assertThat(wrapped.trace().fusedCount()).isEqualTo(baseline.trace().fusedCount());

        // 契约：java 模式不产生任何 Python 调用（连接都不该建立）。
        verifyNoInteractions(gateway);
        assertThat(counter("rerank.calls", "mode", "java", "outcome", "success")).isEqualTo(1.0);
    }

    @Test
    void shadowModeReturnsJavaResultAndDoesNotBlockUserThreadOnPython() throws InterruptedException {
        CountDownLatch pythonEntered = new CountDownLatch(1);
        CountDownLatch releasePython = new CountDownLatch(1);
        when(gateway.rerank(any())).thenAnswer(invocation -> {
            pythonEntered.countDown();
            if (!releasePython.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("测试未及时放行影子调用");
            }
            return response(Map.of(C1, 0.10, C2, 0.90, C3, 0.50, C4, 0.30));
        });

        // 先跑一遍 java 模式作为对照（同时预热管线相关类，避免首次类加载影响 200ms 断言）。
        RetrievalResult javaResult = pipelineWithRouter(RerankMode.JAVA)
                .execute(PROJECT_ID, QUERY, List.of(DOC_ID), config());

        RetrievalPipeline shadowPipeline = pipelineWithRouter(RerankMode.SHADOW);
        long startNanos = System.nanoTime();
        RetrievalResult shadowResult = shadowPipeline.execute(PROJECT_ID, QUERY, List.of(DOC_ID), config());
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;

        // 用户线程可见延迟只包含 Java 计算：Python 调用此刻还被 latch 挡着。
        assertThat(elapsedMs).isLessThan(200);
        assertSameCandidates(javaResult.candidates(), shadowResult.candidates());
        assertThat(shadowResult.trace().rerankerName()).isEqualTo(SemanticHeuristicReranker.NAME);
        assertThat(shadowResult.trace().rerankerDegraded()).isFalse();
        assertThat(shadowResult.candidates()).allSatisfy(
                candidate -> assertThat(candidate.metadata()).doesNotContainKey("rerankerDegraded"));

        // 影子调用确实进了后台（不是根本没发），随后释放并等待异步指标落账。
        assertThat(pythonEntered.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(registry.get("rerank.shadow.topk_overlap").summary().count()).isZero();
        releasePython.countDown();
        awaitUntil(() -> registry.get("rerank.shadow.topk_overlap").summary().count() == 1, 3000);
        // 影子延迟在 runShadowComparison 的 finally 里最后落账，等它出现即可保证前面的计数已完成。
        awaitUntil(() -> registry.get("rerank.shadow.latency").timer().count() == 1, 3000);
        assertThat(counter("rerank.calls", "mode", "shadow", "outcome", "success")).isEqualTo(1.0);
    }

    @Test
    void pythonModeSortsByPythonScoresAndKeepsEvidenceFields() {
        when(gateway.rerank(any())).thenReturn(
                response(Map.of(C1, 0.55, C2, 0.05, C3, 0.20, C4, 0.95)));

        RetrievalResult baseline = pipelineWithoutRouter().execute(PROJECT_ID, QUERY, List.of(DOC_ID), config());
        RetrievalResult result = pipelineWithRouter(RerankMode.PYTHON)
                .execute(PROJECT_ID, QUERY, List.of(DOC_ID), config());

        assertThat(ids(result.candidates())).containsExactly(C4, C1, C3, C2);
        assertThat(result.candidates()).extracting(RetrievalCandidate::rerankScore)
                .containsExactly(0.95, 0.55, 0.20, 0.05);
        assertThat(result.candidates()).allSatisfy(candidate -> {
            assertThat(candidate.rerankerName()).isEqualTo("python-rerank");
            assertThat(candidate.metadata()).doesNotContainKeys("rerankerDegraded", "rerankPartialScore");
        });
        // 证据不可变：id/documentId/heading/content/contentHash/rrfScore 与管线原值一致。
        assertEvidenceUnchanged(baseline.candidates(), result.candidates());
        assertThat(result.trace().rerankerName()).isEqualTo("python-rerank");
        assertThat(result.trace().rerankerDegraded()).isFalse();
        assertThat(counter("rerank.calls", "mode", "python", "outcome", "success")).isEqualTo(1.0);
        assertThat(counter("rerank.calls", "mode", "java", "outcome", "success")).isZero();
        verify(gateway).rerank(any());
    }

    @Test
    void pythonModeFallsBackToHeuristicWithoutFailingTheRequestWhenGatewayUnavailable() {
        when(gateway.rerank(any())).thenThrow(
                new RerankGatewayException(RerankGatewayErrorCode.MODEL_UNAVAILABLE, true, "unavailable"));

        RetrievalResult baseline = pipelineWithoutRouter().execute(PROJECT_ID, QUERY, List.of(DOC_ID), config());
        RetrievalResult result;
        try {
            result = pipelineWithRouter(RerankMode.PYTHON)
                    .execute(PROJECT_ID, QUERY, List.of(DOC_ID), config());
        } catch (RuntimeException failure) {
            throw new AssertionError("python 模式在网关不可用时不得抛出异常", failure);
        }

        // 回退到启发式：顺序与 java 模式完全一致，逐条分数也一致（不是置 0、不是丢候选）。
        assertThat(ids(result.candidates())).containsExactlyElementsOf(ids(baseline.candidates()));
        assertThat(result.candidates()).extracting(RetrievalCandidate::rerankScore)
                .containsExactlyElementsOf(baseline.candidates().stream()
                        .map(RetrievalCandidate::rerankScore)
                        .toList());
        assertThat(result.candidates()).allSatisfy(candidate -> {
            assertThat(candidate.metadata()).containsEntry("rerankerDegraded", true);
            assertThat(candidate.metadata()).containsEntry("rerankSource", "degraded");
        });
        assertEvidenceUnchanged(baseline.candidates(), result.candidates());
        assertThat(result.trace().rerankerDegraded()).isTrue();
        assertThat(result.trace().rerankerName()).isEqualTo("degraded");
        assertThat(counter("rerank.fallback", "reason", "unavailable")).isEqualTo(1.0);
        assertThat(counter("rerank.calls", "mode", "python", "outcome", "fallback")).isEqualTo(1.0);
    }

    @Test
    void passThroughConfigIsNotWrappedEvenInPythonMode() {
        PipelineConfig denseOnly = PipelineConfig.denseOnlyConfig();
        RetrievalResult baseline = pipelineWithoutRouter()
                .execute(PROJECT_ID, QUERY, List.of(DOC_ID), denseOnly);
        RetrievalResult wrapped = pipelineWithRouter(RerankMode.PYTHON)
                .execute(PROJECT_ID, QUERY, List.of(DOC_ID), denseOnly);

        assertSameCandidates(baseline.candidates(), wrapped.candidates());
        assertThat(wrapped.candidates()).allSatisfy(
                candidate -> assertThat(candidate.rerankerName()).isEqualTo("pass-through"));
        assertThat(wrapped.trace().rerankerName()).isEqualTo("pass-through");
        assertThat(wrapped.trace().rerankerDegraded()).isFalse();
        // Dense-only 的"不重排"语义不得被模式开关改写：python 模式下也一次外呼都没有。
        verifyNoInteractions(gateway);
        assertThat(counter("rerank.calls", "mode", "python", "outcome", "success")).isZero();
    }

    // ---------- helpers ----------

    private RetrievalPipeline pipelineWithRouter(RerankMode mode) {
        RerankModeRouter router = new RerankModeRouter(properties(mode), gateway, registry);
        routers.add(router);
        return new RetrievalPipeline(
                denseRetriever,
                lexicalRetriever,
                new ReciprocalRankFusion(),
                new SentenceWindowExpander(),
                new ParentChildExpander(),
                new SemanticHeuristicReranker(),
                null,
                null,
                router);
    }

    /** 6 参构造器：无 mode 包装的既有生产装配路径，作为逐字段对照基线。 */
    private RetrievalPipeline pipelineWithoutRouter() {
        return new RetrievalPipeline(
                denseRetriever,
                lexicalRetriever,
                new ReciprocalRankFusion(),
                new SentenceWindowExpander(),
                new ParentChildExpander(),
                new SemanticHeuristicReranker());
    }

    private static RerankProperties properties(RerankMode mode) {
        return new RerankProperties(mode, "http://127.0.0.1:1", 3000, Duration.ofSeconds(2),
                "", 20, 8, "");
    }

    /** 关闭窗口/父子扩展：这两个扩展器会改写 metadata，会干扰"模式开关"本身的对照。 */
    private static PipelineConfig config() {
        return new PipelineConfig(10, 10, 10, 10, false, 1, false, true,
                RerankerType.LEXICAL_HEURISTIC, List.of(), null);
    }

    private static List<RetrievalCandidate> denseHits() {
        return List.of(
                denseCandidate(C1, "混合架构", "第一句介绍向量检索。第二句介绍混合检索融合算法。", 1, 0.95),
                denseCandidate(C2, "数据库设计", "采用 PostgreSQL pgvector 存储高维向量。", 2, 0.85),
                denseCandidate(C3, "其他说明", "系统遵循不可变证据契约规范。", 3, 0.60));
    }

    private static List<RetrievalCandidate> lexicalHits() {
        return List.of(
                lexicalCandidate(C1, "混合架构", "第一句介绍向量检索。第二句介绍混合检索融合算法。", 1, 0.90),
                lexicalCandidate(C4, "部署拓扑", "worker 通过内部网关回到 Java 调用模型。", 2, 0.80));
    }

    private static RetrievalCandidate denseCandidate(UUID id, String heading, String content, int rank, double score) {
        return new RetrievalCandidate(id, DOC_ID, DOC_NAME, heading, content, "hash-" + id,
                Map.of("bodySnapshotId", "snap-1", "charFrom", 0, "charThrough", content.length()),
                rank, score, null, null, 0.0, null, null);
    }

    private static RetrievalCandidate lexicalCandidate(UUID id, String heading, String content, int rank,
            double score) {
        return new RetrievalCandidate(id, DOC_ID, DOC_NAME, heading, content, "hash-" + id,
                Map.of("bodySnapshotId", "snap-1", "charFrom", 0, "charThrough", content.length()),
                null, null, rank, score, 0.0, null, null);
    }

    private static RerankGatewayResponse response(Map<UUID, Double> scores) {
        Map<String, Double> byId = new LinkedHashMap<>();
        scores.forEach((id, score) -> byId.put(id.toString(), score));
        return new RerankGatewayResponse(1, byId, "rerank-default", RerankGatewayResponse.Usage.unknown());
    }

    private static List<UUID> ids(List<RetrievalCandidate> candidates) {
        return candidates.stream().map(RetrievalCandidate::id).toList();
    }

    private static void assertSameCandidates(List<RetrievalCandidate> expected, List<RetrievalCandidate> actual) {
        assertThat(actual).hasSameSizeAs(expected);
        for (int index = 0; index < expected.size(); index++) {
            RetrievalCandidate left = expected.get(index);
            RetrievalCandidate right = actual.get(index);
            assertThat(right.id()).isEqualTo(left.id());
            assertThat(right.documentId()).isEqualTo(left.documentId());
            assertThat(right.originalFilename()).isEqualTo(left.originalFilename());
            assertThat(right.heading()).isEqualTo(left.heading());
            assertThat(right.content()).isEqualTo(left.content());
            assertThat(right.contentHash()).isEqualTo(left.contentHash());
            assertThat(right.denseRank()).isEqualTo(left.denseRank());
            assertThat(right.denseSimilarity()).isEqualTo(left.denseSimilarity());
            assertThat(right.lexicalRank()).isEqualTo(left.lexicalRank());
            assertThat(right.lexicalScore()).isEqualTo(left.lexicalScore());
            assertThat(right.rrfScore()).isEqualTo(left.rrfScore());
            assertThat(right.rerankScore()).isEqualTo(left.rerankScore());
            assertThat(right.rerankerName()).isEqualTo(left.rerankerName());
            assertThat(right.metadata()).isEqualTo(left.metadata());
        }
    }

    /**
     * 证据不可变校验：模式包装只允许改 rerankScore/rerankerName/新增 metadata 键，
     * 不得改 id/documentId/heading/content/contentHash/rrfScore 与已有 metadata 键。
     * 基线取同一输入下的无 router 管线结果，避免和"融合前"的原始分数混淆。
     */
    private static void assertEvidenceUnchanged(List<RetrievalCandidate> baseline,
            List<RetrievalCandidate> actual) {
        Map<UUID, RetrievalCandidate> byId = new LinkedHashMap<>();
        for (RetrievalCandidate candidate : baseline) {
            byId.put(candidate.id(), candidate);
        }
        for (RetrievalCandidate candidate : actual) {
            RetrievalCandidate original = byId.get(candidate.id());
            assertThat(original).as("模式包装不得丢候选").isNotNull();
            assertThat(candidate.documentId()).isEqualTo(original.documentId());
            assertThat(candidate.originalFilename()).isEqualTo(original.originalFilename());
            assertThat(candidate.heading()).isEqualTo(original.heading());
            assertThat(candidate.content()).isEqualTo(original.content());
            assertThat(candidate.contentHash()).isEqualTo(original.contentHash());
            assertThat(candidate.rrfScore()).isEqualTo(original.rrfScore());
            original.metadata().forEach((key, value) -> assertThat(candidate.metadata()).containsEntry(key, value));
        }
    }

    private double counter(String name, String... tags) {
        io.micrometer.core.instrument.search.Search search = registry.find(name);
        for (int index = 0; index < tags.length; index += 2) {
            search = search.tag(tags[index], tags[index + 1]);
        }
        Counter found = search.counter();
        return found == null ? 0.0 : found.count();
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
}
