package com.shitulelv.aicollab.retrieval.rerank;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.common.net.OutboundHttpClients;
import com.shitulelv.aicollab.internal.ai.token.InternalTokenProvider;
import com.shitulelv.aicollab.internal.ai.token.StaticInternalTokenProvider;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Python rerank worker 的 HTTP 客户端（冻结契约 §1/§3）。
 *
 * <p><b>超时</b>：connect 2s；read {@code deadlineMs + 5s}（服务端硬 deadline 之外的宽限，
 * 用于拿到 worker 明确返回的 504，而不是把 worker 的 DEADLINE_EXCEEDED 误判成网络故障）。</p>
 *
 * <p><b>重试</b>：只对"连接失败 / 502 / 503"重试，指数退避 100ms、200ms，最多 2 次
 * （总尝试 3 次）。4xx、读超时、非法 JSON 一律不重试——它们不是瞬时故障，
 * 重试只会放大延迟并可能重复计费。</p>
 *
 * <p><b>幂等</b>：{@code Idempotency-Key = sha256(projectId|query|topK|id1,id2…)[0:32]}，
 * 同一次逻辑调用无论重试多少次都保持不变；{@code X-Call-Id} 携带 attempt 便于 worker 侧
 * 对账。</p>
 *
 * <p><b>日志红线</b>：只记录 callId、候选条数、耗时、错误码；<b>绝不</b>记录 query、
 * 候选正文、服务令牌或响应原文。</p>
 *
 * <p><b>指标（W3.1）</b>：每次<b>逻辑调用</b>记一条
 * {@code rerank.worker.duration{outcome=success|retry_success|failure}}（含重试的总耗时）；
 * 每次真实重试记 {@code rerank.worker.retries{attempt="2"|"3"}}。标签只有稳定低基数值，
 * 不带 query/候选 id/错误文本。</p>
 */
@Component
public class RerankGatewayClient {

    private static final Logger log = LoggerFactory.getLogger(RerankGatewayClient.class);

    static final String RERANK_PATH = "/v1/rag/rerank";
    static final int MAX_ATTEMPTS = 3;
    static final String METRIC_WORKER_DURATION = "rerank.worker.duration";
    static final String METRIC_WORKER_RETRIES = "rerank.worker.retries";
    private static final long READ_TIMEOUT_GRACE_MS = 5_000L;
    private static final long MAX_RESPONSE_BYTES = 4L * 1024 * 1024;
    private static final int MIN_TOP_K = 1;
    private static final int MAX_TOP_K = 50;
    private static final Pattern TRACE_ID = Pattern.compile("[0-9a-f]{32}");
    private static final Pattern SPAN_ID = Pattern.compile("[0-9a-f]{16}");

    private static final String OUTCOME_SUCCESS = "success";
    private static final String OUTCOME_RETRY_SUCCESS = "retry_success";
    private static final String OUTCOME_FAILURE = "failure";

    private final RerankProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final MeterRegistry meterRegistry;
    private final InternalTokenProvider tokenProvider;
    private final Timer successTimer;
    private final Timer retrySuccessTimer;
    private final Timer failureTimer;

    @Autowired
    public RerankGatewayClient(RerankProperties properties, ObjectMapper objectMapper, MeterRegistry meterRegistry,
            InternalTokenProvider tokenProvider) {
        this(properties, objectMapper, defaultHttpClient(properties), meterRegistry, tokenProvider);
    }

    /** 测试便捷构造器（无自定义 registry）：指标只落在独立的 SimpleMeterRegistry 上。 */
    RerankGatewayClient(RerankProperties properties, ObjectMapper objectMapper) {
        this(properties, objectMapper, defaultHttpClient(properties), new SimpleMeterRegistry());
    }

    /** 测试可注入自定义 registry（使用默认 HttpClient）。 */
    RerankGatewayClient(RerankProperties properties, ObjectMapper objectMapper, MeterRegistry meterRegistry) {
        this(properties, objectMapper, defaultHttpClient(properties), meterRegistry);
    }

    /** 测试可注入自定义 HttpClient（例如固定 HTTP/1.1 的本地 stub 连接）。 */
    RerankGatewayClient(RerankProperties properties, ObjectMapper objectMapper, HttpClient httpClient) {
        this(properties, objectMapper, httpClient, new SimpleMeterRegistry());
    }

    RerankGatewayClient(RerankProperties properties, ObjectMapper objectMapper, HttpClient httpClient,
            MeterRegistry meterRegistry) {
        this(properties, objectMapper, httpClient, meterRegistry,
                new StaticInternalTokenProvider(properties.serviceToken()));
    }

    /** 测试可注入固定令牌提供者（断言每次调用都重新取令牌）。 */
    RerankGatewayClient(RerankProperties properties, ObjectMapper objectMapper, HttpClient httpClient,
            MeterRegistry meterRegistry, InternalTokenProvider tokenProvider) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry");
        this.tokenProvider = tokenProvider == null
                ? new StaticInternalTokenProvider(properties.serviceToken())
                : tokenProvider;
        this.successTimer = newWorkerTimer(meterRegistry, OUTCOME_SUCCESS);
        this.retrySuccessTimer = newWorkerTimer(meterRegistry, OUTCOME_RETRY_SUCCESS);
        this.failureTimer = newWorkerTimer(meterRegistry, OUTCOME_FAILURE);
    }

    private static HttpClient defaultHttpClient(RerankProperties properties) {
        return OutboundHttpClients.builder()
                .connectTimeout(properties.connectTimeout())
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    private static Timer newWorkerTimer(MeterRegistry registry, String outcome) {
        return Timer.builder(METRIC_WORKER_DURATION)
                .tag("outcome", outcome)
                .publishPercentileHistogram()
                .register(registry);
    }

    /**
     * 调用 rerank worker。
     *
     * @throws RerankGatewayException 所有失败都转成稳定的错误分类；调用方负责降级
     */
    public RerankGatewayResponse rerank(RerankGatewayRequest request) {
        Objects.requireNonNull(request, "request");
        long startNanos = System.nanoTime();
        try {
            String body = serialize(request);
            String idempotencyKey = idempotencyKey(request, body);
            RerankGatewayException lastFailure = null;
            for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
                long attemptStartNanos = System.nanoTime();
                if (attempt > 1) {
                    recordRetry(attempt);
                }
                try {
                    RerankGatewayResponse response = postOnce(request, body, idempotencyKey, attempt,
                            attemptStartNanos);
                    if (attempt > 1) {
                        log.info("rerank worker 重试成功: callId={} attempt={} ids={} elapsedMs={}",
                                callId(request, idempotencyKey, attempt), attempt,
                                request.candidates().size(), elapsedMillis(attemptStartNanos));
                    }
                    recordWorkerDuration(attempt > 1 ? OUTCOME_RETRY_SUCCESS : OUTCOME_SUCCESS,
                            System.nanoTime() - startNanos);
                    return response;
                } catch (RerankGatewayException failure) {
                    lastFailure = failure;
                    log.warn("rerank worker 调用失败: callId={} attempt={} ids={} elapsedMs={} code={} retryable={}",
                            callId(request, idempotencyKey, attempt), attempt, request.candidates().size(),
                            elapsedMillis(attemptStartNanos), failure.code(), failure.isRetryable());
                    if (!failure.isRetryable() || attempt == MAX_ATTEMPTS) {
                        throw failure;
                    }
                    sleepBeforeRetry(attempt);
                }
            }
            throw lastFailure;
        } catch (RerankGatewayException failure) {
            recordWorkerDuration(OUTCOME_FAILURE, System.nanoTime() - startNanos);
            throw failure;
        }
    }

    /**
     * 逻辑调用的幂等作用域：{@code <projectId|unknown>:<idempotencyKey 前 8>}。
     *
     * <p>供 {@link RerankModeRouter} 打固定字段的结构化日志（与 {@code X-Call-Id} 的前缀
     * 三元组一致）；不包含 query/正文。包内可见，避免把该细节暴露成公共 API。</p>
     */
    String callScope(RerankGatewayRequest request) {
        Objects.requireNonNull(request, "request");
        String body = serialize(request);
        String scope = request.projectId() == null ? "unknown" : request.projectId().toString();
        return scope + ":" + idempotencyKey(request, body).substring(0, 8);
    }

    private void recordWorkerDuration(String outcome, long elapsedNanos) {
        try {
            Timer timer = switch (outcome) {
                case OUTCOME_RETRY_SUCCESS -> retrySuccessTimer;
                case OUTCOME_FAILURE -> failureTimer;
                default -> successTimer;
            };
            timer.record(Math.max(0L, elapsedNanos), TimeUnit.NANOSECONDS);
        } catch (RuntimeException ignored) {
            // 指标不得改变业务结果（沿 PromptCatalogMetrics 约定）
        }
    }

    private void recordRetry(int attempt) {
        try {
            Counter.builder(METRIC_WORKER_RETRIES)
                    .tag("attempt", Integer.toString(attempt))
                    .register(meterRegistry)
                    .increment();
        } catch (RuntimeException ignored) {
            // 指标不得改变业务结果
        }
    }

    private RerankGatewayResponse postOnce(RerankGatewayRequest request, String body,
            String idempotencyKey, int attempt, long startNanos) {
        HttpRequest httpRequest = buildHttpRequest(request, body, idempotencyKey, attempt);
        HttpResponse<byte[]> response;
        try {
            response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofByteArray());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new RerankGatewayException(RerankGatewayErrorCode.MODEL_UNAVAILABLE, false,
                    "rerank worker 调用被中断");
        } catch (IOException transportFailure) {
            throw classifyTransportFailure(transportFailure);
        }

        int status = response.statusCode();
        byte[] payload = response.body() == null ? new byte[0] : response.body();
        if (payload.length > MAX_RESPONSE_BYTES) {
            throw new RerankGatewayException(RerankGatewayErrorCode.SCHEMA_VIOLATION, false,
                    "rerank worker 响应体超过上限");
        }
        if (status < 200 || status >= 300) {
            throw httpFailure(status);
        }
        RerankGatewayResponse parsed = parse(request, payload);
        log.debug("rerank worker 调用成功: callId={} attempt={} ids={} elapsedMs={}",
                callId(request, idempotencyKey, attempt), attempt, request.candidates().size(),
                elapsedMillis(startNanos));
        return parsed;
    }

    private HttpRequest buildHttpRequest(RerankGatewayRequest request, String body,
            String idempotencyKey, int attempt) {
        int deadlineMs = effectiveDeadlineMs(request);
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(properties.workerBaseUrl() + RERANK_PATH))
                .timeout(Duration.ofMillis(deadlineMs + READ_TIMEOUT_GRACE_MS))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("Idempotency-Key", idempotencyKey)
                .header("X-Call-Id", callId(request, idempotencyKey, attempt))
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        String bearerToken = currentToken();
        if (!bearerToken.isEmpty()) {
            builder.header("Authorization", "Bearer " + bearerToken);
        }
        String traceparent = currentTraceparent();
        if (traceparent != null) {
            builder.header("traceparent", traceparent);
        }
        return builder.build();
    }

    /**
     * 每次 HTTP 尝试前取一次令牌：{@code ed25519}/{@code dual} 模式下每请求都是新签名，
     * {@code static} 模式返回固定值（行为不变）。令牌为空时不发 Authorization 头。
     */
    private String currentToken() {
        try {
            String token = tokenProvider.token();
            return token == null ? "" : token.trim();
        } catch (RuntimeException failure) {
            // 签发失败属于服务端配置/实现问题；不重试，也不把异常文本（可能含密钥材料）透出。
            throw new RerankGatewayException(RerankGatewayErrorCode.INTERNAL, false,
                    "内部服务令牌签发失败");
        }
    }

    private String serialize(RerankGatewayRequest request) {
        List<PayloadCandidate> candidates = new ArrayList<>(request.candidates().size());
        for (RerankGatewayRequest.Candidate candidate : request.candidates()) {
            candidates.add(new PayloadCandidate(
                    candidate.id(),
                    candidate.title() == null ? "" : candidate.title(),
                    candidate.text() == null ? "" : candidate.text()));
        }
        Payload payload = new Payload(1, request.query() == null ? "" : request.query(),
                effectiveTopK(request), candidates, effectiveDeadlineMs(request));
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (IOException serializationFailure) {
            throw new RerankGatewayException(RerankGatewayErrorCode.INTERNAL, false,
                    "rerank 请求序列化失败");
        }
    }

    /**
     * 幂等键必须覆盖正文：同一批 id 在文档重建后正文可能变化，只哈希 id 会命中 worker
     * 10 分钟内的旧缓存分数。这里对整个序列化请求体（含 projectId 作用域，重试不变）
     * 做 sha256，取前 32 hex。
     */
    private String idempotencyKey(RerankGatewayRequest request, String body) {
        String scope = request.projectId() == null ? "" : request.projectId().toString();
        return sha256Hex(scope + "|" + body).substring(0, 32);
    }

    private String callId(RerankGatewayRequest request, String idempotencyKey, int attempt) {
        String scope = request.projectId() == null ? "unknown" : request.projectId().toString();
        return "rerank:" + scope + ":" + idempotencyKey.substring(0, 8) + ":" + attempt;
    }

    private RerankGatewayResponse parse(RerankGatewayRequest request, byte[] payload) {
        JsonNode root;
        try {
            root = objectMapper.readTree(payload);
        } catch (IOException invalidJson) {
            throw schemaViolation("响应不是合法 JSON");
        }
        if (root == null || !root.isObject()) {
            throw schemaViolation("响应不是 JSON 对象");
        }
        JsonNode schemaNode = root.get("schemaVersion");
        if (schemaNode == null || !schemaNode.isIntegralNumber() || schemaNode.asInt() != 1) {
            throw schemaViolation("schemaVersion 不匹配");
        }
        JsonNode scoresNode = root.get("scores");
        if (scoresNode == null || !scoresNode.isArray() || scoresNode.isEmpty()) {
            throw schemaViolation("scores 缺失或为空");
        }

        Set<String> expectedIds = new LinkedHashSet<>();
        for (RerankGatewayRequest.Candidate candidate : request.candidates()) {
            expectedIds.add(candidate.id());
        }
        Map<String, Double> scores = new LinkedHashMap<>();
        for (JsonNode item : scoresNode) {
            if (item == null || !item.isObject()) {
                throw schemaViolation("scores 条目不是对象");
            }
            JsonNode idNode = item.get("id");
            JsonNode scoreNode = item.get("score");
            if (idNode == null || !idNode.isTextual() || idNode.asText().isBlank()) {
                throw schemaViolation("scores 条目缺少 id");
            }
            String id = idNode.asText();
            if (!expectedIds.contains(id)) {
                throw schemaViolation("scores 含未知 id");
            }
            if (scoreNode == null || !scoreNode.isNumber()) {
                throw schemaViolation("scores 条目缺少分数");
            }
            double score = scoreNode.asDouble();
            if (!Double.isFinite(score) || score < 0.0 || score > 1.0) {
                throw schemaViolation("分数越界");
            }
            if (scores.putIfAbsent(id, score) != null) {
                throw schemaViolation("scores 含重复 id");
            }
        }
        String modelAlias = null;
        JsonNode aliasNode = root.get("modelAlias");
        if (aliasNode != null && aliasNode.isTextual()) {
            modelAlias = aliasNode.asText();
        }
        // 可选扩展字段：非负整数才认，缺失/负数/非整数一律 UNKNOWN（null），不填 0 冒充。
        Integer modelLatencyMs = integerOrNull(root.get("modelLatencyMs"));
        if (modelLatencyMs != null && modelLatencyMs < 0) {
            modelLatencyMs = null;
        }
        return new RerankGatewayResponse(1, scores, modelAlias, parseUsage(root.get("usage")), modelLatencyMs);
    }

    private static RerankGatewayResponse.Usage parseUsage(JsonNode usageNode) {
        if (usageNode == null || !usageNode.isObject()) {
            return RerankGatewayResponse.Usage.unknown();
        }
        Integer inputTokens = integerOrNull(usageNode.get("inputTokens"));
        Integer outputTokens = integerOrNull(usageNode.get("outputTokens"));
        String source = usageNode.hasNonNull("source") ? usageNode.get("source").asText() : "UNKNOWN";
        if (!"PROVIDER".equals(source) && !"ESTIMATE".equals(source) && !"UNKNOWN".equals(source)) {
            source = "UNKNOWN";
        }
        return new RerankGatewayResponse.Usage(inputTokens, outputTokens, source);
    }

    private static Integer integerOrNull(JsonNode node) {
        return node != null && node.isIntegralNumber() ? node.asInt() : null;
    }

    private static RerankGatewayException schemaViolation(String message) {
        return new RerankGatewayException(RerankGatewayErrorCode.SCHEMA_VIOLATION, false, message);
    }

    private static RerankGatewayException httpFailure(int status) {
        if (status == 429) {
            return new RerankGatewayException(RerankGatewayErrorCode.MODEL_RATE_LIMITED, false,
                    "rerank worker 限流");
        }
        if (status == 504) {
            // worker 的 DEADLINE_EXCEEDED：服务端 deadline 已经耗尽，用同一 deadline 重试
            // 只会把用户等待放大到 3 倍（staging 实测 9 个候选 ≈ 9s），因此不重试，直接回退。
            return new RerankGatewayException(RerankGatewayErrorCode.DEADLINE_EXCEEDED, false,
                    "rerank worker 超过 deadline");
        }
        if (status == 502 || status == 503) {
            return new RerankGatewayException(RerankGatewayErrorCode.MODEL_UNAVAILABLE, true,
                    "rerank worker 暂不可用");
        }
        if (status >= 500) {
            return new RerankGatewayException(RerankGatewayErrorCode.MODEL_UNAVAILABLE, false,
                    "rerank worker 内部错误");
        }
        if (status >= 400) {
            return new RerankGatewayException(RerankGatewayErrorCode.INVALID_REQUEST, false,
                    "rerank worker 拒绝了请求");
        }
        return new RerankGatewayException(RerankGatewayErrorCode.INTERNAL, false,
                "rerank worker 返回非预期状态");
    }

    /**
     * 传输层异常分类（包内可见以便单元测试覆盖读超时分支，避免真实等待 5s）：
     * 连接失败可重试，读超时不重试。
     */
    static RerankGatewayException classifyTransportFailure(Throwable failure) {
        if (failure instanceof HttpConnectTimeoutException) {
            return new RerankGatewayException(RerankGatewayErrorCode.MODEL_UNAVAILABLE, true,
                    "rerank worker 连接超时");
        }
        if (failure instanceof HttpTimeoutException) {
            return new RerankGatewayException(RerankGatewayErrorCode.DEADLINE_EXCEEDED, false,
                    "rerank worker 读取超时");
        }
        if (failure instanceof ConnectException) {
            return new RerankGatewayException(RerankGatewayErrorCode.MODEL_UNAVAILABLE, true,
                    "rerank worker 连接失败");
        }
        if (failure instanceof IOException) {
            return new RerankGatewayException(RerankGatewayErrorCode.MODEL_UNAVAILABLE, true,
                    "rerank worker 网络异常");
        }
        return new RerankGatewayException(RerankGatewayErrorCode.INTERNAL, false,
                "rerank worker 调用异常");
    }

    private static void sleepBeforeRetry(int attempt) {
        long backoffMs = attempt == 1 ? 100L : 200L;
        try {
            Thread.sleep(backoffMs);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new RerankGatewayException(RerankGatewayErrorCode.INTERNAL, false,
                    "rerank 重试被中断");
        }
    }

    private static int effectiveTopK(RerankGatewayRequest request) {
        int topK = request.topK();
        if (topK < MIN_TOP_K) {
            return MIN_TOP_K;
        }
        return Math.min(topK, MAX_TOP_K);
    }

    private static int effectiveDeadlineMs(RerankGatewayRequest request) {
        int deadlineMs = request.deadlineMs();
        if (deadlineMs < 1) {
            return 1;
        }
        return Math.min(deadlineMs, 30_000);
    }

    /**
     * 透传当前 trace 上下文（有则带）。使用 micrometer-tracing 注入 MDC 的 traceId/spanId，
     * 格式不合法时宁可不带，也不发一个破坏 W3C 契约的 traceparent。
     */
    static String currentTraceparent() {
        String traceId = MDC.get("traceId");
        String spanId = MDC.get("spanId");
        if (traceId == null || spanId == null) {
            return null;
        }
        if (!TRACE_ID.matcher(traceId).matches() || !SPAN_ID.matcher(spanId).matches()) {
            return null;
        }
        return "00-" + traceId + "-" + spanId + "-01";
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException unreachable) {
            throw new IllegalStateException("SHA-256 不可用", unreachable);
        }
    }

    private static long elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    private record Payload(int schemaVersion, String query, int topK,
            List<PayloadCandidate> candidates, int deadlineMs) {
    }

    private record PayloadCandidate(String id, String title, String text) {
    }
}
