package com.shitulelv.aicollab.retrieval.rerank;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.retrieval.pipeline.Reranker;
import com.shitulelv.aicollab.retrieval.pipeline.RetrievalCandidate;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * W3.1 测量 A-Java（冻结契约 §1.7）：Java 单测内嵌 stub worker，验证 Java 侧
 * {@code rerank.overhead} 仪表与门禁（P95 ≤800ms、P99 ≤1500ms，最近秩法）。
 *
 * <p>口径：stub 固定返回 {@code modelLatencyMs=0}，因此 {@code T_model=0}、
 * {@code T_overhead = max(0, T_total_java - 0) = T_total_java}。inline 产品边界固定为
 * 8 候选，因此这里只对 8 候选跑 510 次（≥500 stub 样本）；20/50 属于
 * shadow/offline，不得从 inline 路由发送。</p>
 *
 * <p>实测 P50/P95/P99 与样本数打印到 stdout，作为测试证据；门禁断言只认 P95/P99。</p>
 */
class RerankOverheadBudgetTest {

    private static final int RUNS_PER_CANDIDATE_COUNT = 510;
    private static final int WARMUP_RUNS = 12;
    private static final int[] CANDIDATE_COUNTS = {8};
    private static final double P95_BUDGET_MS = 800.0;
    private static final double P99_BUDGET_MS = 1500.0;
    private static final UUID DOC_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final String QUERY = "混合架构 向量检索";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    @Test
    void javaSideOverheadMeetsBudgetAgainstStubWorkerWithKnownModelLatency() throws IOException {
        try (StubWorker worker = new StubWorker(true)) {
            RerankProperties properties = properties(worker.baseUrl());
            RerankModeRouter router = new RerankModeRouter(properties,
                    new RerankGatewayClient(properties, objectMapper), registry);
            try {
                Reranker wrapped = router.wrap(new EchoReranker(), UUID.randomUUID());
                // 预热：避免首次类加载/JIT/HTTP 连接建立污染门禁样本。
                List<RetrievalCandidate> warmupCandidates = candidates(8);
                for (int run = 0; run < WARMUP_RUNS; run++) {
                    wrapped.rerank(QUERY, warmupCandidates, 8);
                }

                List<Double> samples = new ArrayList<>(512);
                for (int candidateCount : CANDIDATE_COUNTS) {
                    List<RetrievalCandidate> candidates = candidates(candidateCount);
                    for (int run = 0; run < RUNS_PER_CANDIDATE_COUNT; run++) {
                        long startNanos = System.nanoTime();
                        List<RetrievalCandidate> result = wrapped.rerank(QUERY, candidates, candidateCount);
                        double totalMs = (System.nanoTime() - startNanos) / 1_000_000.0;
                        assertThat(result).hasSize(candidateCount);
                        // stub 的 modelLatencyMs=0 → T_overhead = max(0, total - 0)。
                        samples.add(Math.max(0.0, totalMs));
                    }
                }

                assertThat(samples).hasSize(RUNS_PER_CANDIDATE_COUNT * CANDIDATE_COUNTS.length);
                Timer overhead = registry.get("rerank.overhead")
                        .tag("mode", "python").tag("model_latency_known", "true").timer();
                // 预热调用也走同一 python 路径，会各记一条 overhead；门禁样本只取上面 510 条。
                assertThat(overhead.count()).isEqualTo(samples.size() + WARMUP_RUNS);
                assertThat(registry.get("rerank.model_latency_known")
                        .tag("known", "true").counter().count())
                        .isEqualTo(samples.size() + WARMUP_RUNS);

                List<Double> sorted = samples.stream().sorted().toList();
                double p50 = percentile(sorted, 50);
                double p95 = percentile(sorted, 95);
                double p99 = percentile(sorted, 99);
                System.out.printf(
                        "[overhead-budget] candidate_counts=8 samples=%d p50=%.3fms p95=%.3fms p99=%.3fms "
                                + "gate=P95<=%.0fms,P99<=%.0fms%n",
                        sorted.size(), p50, p95, p99, P95_BUDGET_MS, P99_BUDGET_MS);
                assertThat(p95).as("overhead P95").isLessThanOrEqualTo(P95_BUDGET_MS);
                assertThat(p99).as("overhead P99").isLessThanOrEqualTo(P99_BUDGET_MS);
            } finally {
                router.shutdown();
            }
        }
    }

    @Test
    void missingModelLatencyMarksUnknownAndProducesNoGateSample() throws IOException {
        try (StubWorker worker = new StubWorker(false)) {
            RerankProperties properties = properties(worker.baseUrl());
            RerankModeRouter router = new RerankModeRouter(properties,
                    new RerankGatewayClient(properties, objectMapper), registry);
            try {
                Reranker wrapped = router.wrap(new EchoReranker(), UUID.randomUUID());
                List<RetrievalCandidate> result = wrapped.rerank(QUERY, candidates(8), 8);

                assertThat(result).hasSize(8);
                assertThat(registry.get("rerank.model_latency_known")
                        .tag("known", "false").counter().count()).isEqualTo(1.0);
                assertThat(registry.get("rerank.overhead")
                        .tag("mode", "python").tag("model_latency_known", "false")
                        .timer().count()).isEqualTo(1);
                // 无 modelLatencyMs 的响应不得产生门禁样本（known=true）。
                assertThat(registry.find("rerank.overhead")
                        .tag("model_latency_known", "true").timer()).isNull();
            } finally {
                router.shutdown();
            }
        }
    }

    // ---------- helpers ----------

    private RerankProperties properties(String baseUrl) {
        return new RerankProperties(RerankMode.PYTHON, baseUrl, 3000, Duration.ofSeconds(2),
                "", 50, 8, 1.0, "");
    }

    /** 最近秩法（nearest-rank）：rank = ceil(p/100 × n)，取第 rank 个（1-based）。 */
    private static double percentile(List<Double> sortedAscending, double percentile) {
        int size = sortedAscending.size();
        int rank = (int) Math.ceil(percentile / 100.0 * size);
        int index = Math.min(size - 1, Math.max(0, rank - 1));
        return sortedAscending.get(index);
    }

    private static List<RetrievalCandidate> candidates(int count) {
        List<RetrievalCandidate> candidates = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            UUID id = new UUID(0x1000L, index + 1L);
            String heading = "候选标题-" + (index + 1);
            String content = ("混合检索与向量召回的证据片段 " + (index + 1) + "。")
                    + "pgvector 存储高维向量，RRF 融合后交给重排器打分。".repeat(8);
            candidates.add(new RetrievalCandidate(id, DOC_ID, "architecture.md", heading, content,
                    "hash-" + index, Map.of("bodySnapshotId", "snap-1", "charFrom", 0,
                            "charThrough", content.length()),
                    1, 0.9, null, null, 1.0 - index * 0.01, null, null));
        }
        return candidates;
    }

    /** 只做透传的替身：stub 覆盖全部 id 时 python 路径不会调用它。 */
    private static final class EchoReranker implements Reranker {

        @Override
        public String name() {
            return "echo";
        }

        @Override
        public List<RetrievalCandidate> rerank(String query, List<RetrievalCandidate> candidates, int topN) {
            List<RetrievalCandidate> result = new ArrayList<>(candidates.size());
            for (RetrievalCandidate candidate : candidates) {
                result.add(candidate.withRerank(candidate.rrfScore() != null ? candidate.rrfScore() : 0.0, name()));
            }
            result.sort(Comparator
                    .comparingDouble((RetrievalCandidate candidate) -> candidate.rerankScore()).reversed()
                    .thenComparing(candidate -> candidate.id().toString()));
            if (topN > 0 && result.size() > topN) {
                return new ArrayList<>(result.subList(0, topN));
            }
            return result;
        }
    }

    /** 本地 HTTP/1.1 stub worker：按请求 candidates 原样回 score=0.5，可选 modelLatencyMs=0。 */
    private final class StubWorker implements AutoCloseable {

        private final HttpServer server;
        private final ExecutorService executor;
        private final boolean includeModelLatency;

        private StubWorker(boolean includeModelLatency) throws IOException {
            this.includeModelLatency = includeModelLatency;
            this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            this.executor = Executors.newCachedThreadPool(runnable -> {
                Thread thread = new Thread(runnable, "rerank-overhead-stub");
                thread.setDaemon(true);
                return thread;
            });
            server.setExecutor(executor);
            server.createContext(RerankGatewayClient.RERANK_PATH, exchange -> {
                try {
                    byte[] requestBytes = exchange.getRequestBody().readAllBytes();
                    String responseBody = responseBody(requestBytes);
                    byte[] payload = responseBody.getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
                    exchange.sendResponseHeaders(200, payload.length);
                    try (OutputStream output = exchange.getResponseBody()) {
                        output.write(payload);
                    }
                } catch (RuntimeException | IOException failure) {
                    try {
                        exchange.sendResponseHeaders(500, -1);
                    } catch (IOException ignored) {
                        // stub 只能尽力而为，客户端会按 5xx/传输错误分类
                    } finally {
                        exchange.close();
                    }
                }
            });
            server.start();
        }

        private String responseBody(byte[] requestBytes) throws IOException {
            JsonNode root = objectMapper.readTree(requestBytes);
            JsonNode candidates = root == null ? null : root.get("candidates");
            if (candidates == null || !candidates.isArray() || candidates.isEmpty()) {
                throw new IOException("stub 收到非法请求：缺少 candidates");
            }
            StringBuilder body = new StringBuilder(64 + candidates.size() * 40);
            body.append("{\"schemaVersion\":1,\"scores\":[");
            for (int index = 0; index < candidates.size(); index++) {
                if (index > 0) {
                    body.append(',');
                }
                body.append("{\"id\":\"")
                        .append(candidates.get(index).get("id").asText())
                        .append("\",\"score\":0.5}");
            }
            body.append("],\"modelAlias\":\"rerank-default\",")
                    .append("\"usage\":{\"inputTokens\":0,\"outputTokens\":0,\"source\":\"ESTIMATE\"}");
            if (includeModelLatency) {
                body.append(",\"modelLatencyMs\":0");
            }
            body.append('}');
            return body.toString();
        }

        private String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        @Override
        public void close() {
            server.stop(0);
            executor.shutdownNow();
        }
    }
}
