package com.shitulelv.aicollab.agent.infrastructure.decide;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.common.net.OutboundHttpClients;
import com.shitulelv.aicollab.retrieval.rerank.RerankProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Set;

/**
 * Python decide worker 的 HTTP 客户端（W4 冻结契约 §2/§3.3）。
 *
 * <p><b>超时</b>：connect 2s；read {@code deadlineMs + 5s}（服务端硬 deadline 之外的宽限，
 * 用于拿到 worker 明确返回的 504/422，而不是把 worker 的明确失败误判成网络故障）。</p>
 *
 * <p><b>重试</b>：<b>不重试</b>。影子调用不得放大延迟；端点天然幂等，失败只记指标。</p>
 *
 * <p><b>幂等</b>：{@code Idempotency-Key = sha256(runId|attempt|turnNo|snapshotHash)[0:32]}；
 * {@code X-Call-Id} 取同一键的前 16 位，便于 worker 侧对账。</p>
 *
 * <p><b>鉴权</b>：{@code Authorization: Bearer <service-token>}；未配置时不发该头，
 * 由 worker 侧 fail closed。服务令牌本波复用 {@code rerank.service-token}，
 * 收敛在唯一的私有方法 {@link #serviceToken()} 里，W3.2 之后替换为内部令牌提供者。</p>
 *
 * <p><b>日志红线</b>：只记录 callId、HTTP 状态、耗时与失败原因；<b>绝不</b>记录 Prompt、
 * 快照内容、工具参数、响应正文或服务令牌。</p>
 */
@Component
public class DecideGatewayClient {

    private static final Logger log = LoggerFactory.getLogger(DecideGatewayClient.class);

    static final String DECIDE_PATH = "/v1/agent/decide";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration DEFAULT_READ_TIMEOUT_GRACE = Duration.ofSeconds(5);
    private static final long MAX_RESPONSE_BYTES = 1024L * 1024L;
    private static final int MIN_DEADLINE_MS = 1;
    private static final int MAX_DEADLINE_MS = 600_000;
    private static final int CALL_ID_CHARS = 16;

    private final DecideProperties properties;
    private final RerankProperties rerankProperties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final Duration readTimeoutGrace;

    @Autowired
    public DecideGatewayClient(DecideProperties properties, RerankProperties rerankProperties,
            ObjectMapper objectMapper) {
        this(properties, rerankProperties, objectMapper,
                OutboundHttpClients.builder()
                        .connectTimeout(CONNECT_TIMEOUT)
                        .version(HttpClient.Version.HTTP_1_1)
                        .build(),
                DEFAULT_READ_TIMEOUT_GRACE);
    }

    /**
     * 测试可注入自定义 HttpClient 与读取宽限（用短宽限构造真实超时场景，
     * 避免断言 5s 的等待）。
     */
    DecideGatewayClient(DecideProperties properties, RerankProperties rerankProperties,
            ObjectMapper objectMapper, HttpClient httpClient, Duration readTimeoutGrace) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.rerankProperties = Objects.requireNonNull(rerankProperties, "rerankProperties");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.readTimeoutGrace = readTimeoutGrace == null || readTimeoutGrace.isNegative()
                ? DEFAULT_READ_TIMEOUT_GRACE : readTimeoutGrace;
    }

    /**
     * 调用 decide worker。
     *
     * @throws DecideGatewayException 所有失败都转成稳定的原因分类；调用方只记指标，不重试
     */
    public DecideGatewayResponse decide(DecideGatewayRequest request) {
        Objects.requireNonNull(request, "request");
        String body = serialize(request);
        String idempotencyKey = idempotencyKey(request);
        String callId = idempotencyKey.substring(0, CALL_ID_CHARS);
        long startNanos = System.nanoTime();

        HttpResponse<byte[]> response;
        try {
            response = httpClient.send(buildHttpRequest(request, body, idempotencyKey, callId),
                    HttpResponse.BodyHandlers.ofByteArray());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            log.warn("decide worker 调用被中断: callId={} elapsedMs={} reason={}",
                    callId, elapsedMillis(startNanos), DecideGatewayErrorCode.UNEXPECTED.tag());
            throw new DecideGatewayException(DecideGatewayErrorCode.UNEXPECTED, "decide worker 调用被中断");
        } catch (IOException transportFailure) {
            DecideGatewayException classified = classifyTransportFailure(transportFailure);
            log.warn("decide worker 传输失败: callId={} elapsedMs={} reason={}",
                    callId, elapsedMillis(startNanos), classified.reason().tag());
            throw classified;
        }

        int status = response.statusCode();
        byte[] payload = response.body() == null ? new byte[0] : response.body();
        if (status < 200 || status >= 300) {
            DecideGatewayException failure = httpFailure(status);
            log.warn("decide worker 返回非 2xx: callId={} status={} elapsedMs={} reason={}",
                    callId, status, elapsedMillis(startNanos), failure.reason().tag());
            throw failure;
        }
        if (payload.length > MAX_RESPONSE_BYTES) {
            log.warn("decide worker 响应体超过上限: callId={} status={} elapsedMs={} reason={}",
                    callId, status, elapsedMillis(startNanos), DecideGatewayErrorCode.SCHEMA.tag());
            throw new DecideGatewayException(DecideGatewayErrorCode.SCHEMA, "decide worker 响应体超过上限");
        }

        DecideGatewayResponse parsed;
        try {
            parsed = parse(request, payload);
        } catch (DecideGatewayException contractFailure) {
            log.warn("decide worker 响应违反契约: callId={} status={} elapsedMs={} reason={}",
                    callId, status, elapsedMillis(startNanos), contractFailure.reason().tag());
            throw contractFailure;
        }
        log.debug("decide worker 调用成功: callId={} status={} elapsedMs={}",
                callId, status, elapsedMillis(startNanos));
        return parsed;
    }

    private HttpRequest buildHttpRequest(DecideGatewayRequest request, String body,
            String idempotencyKey, String callId) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(properties.workerBaseUrl() + DECIDE_PATH))
                .timeout(Duration.ofMillis(effectiveDeadlineMs(request) + readTimeoutGrace.toMillis()))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("Idempotency-Key", idempotencyKey)
                .header("X-Call-Id", callId)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        String token = serviceToken();
        if (!token.isEmpty()) {
            builder.header("Authorization", "Bearer " + token);
        }
        return builder.build();
    }

    /**
     * 内部服务令牌的唯一读取点（本波复用 {@code rerank.service-token}）。
     * W3.2 引入内部令牌提供者后只改这里，调用方无感；未配置时返回空串，不发送鉴权头。
     */
    private String serviceToken() {
        String token = rerankProperties.serviceToken();
        return token == null ? "" : token.trim();
    }

    private String serialize(DecideGatewayRequest request) {
        try {
            return objectMapper.writeValueAsString(request);
        } catch (IOException serializationFailure) {
            throw new DecideGatewayException(DecideGatewayErrorCode.UNEXPECTED,
                    "decide 请求序列化失败");
        }
    }

    /** {@code sha256(runId|attempt|turnNo|snapshotHash)[0:32]}。 */
    private static String idempotencyKey(DecideGatewayRequest request) {
        String scope = nullToEmpty(request.runId()) + "|" + request.attempt() + "|"
                + request.turnNo() + "|" + nullToEmpty(request.snapshotHash());
        return sha256Hex(scope).substring(0, 32);
    }

    private DecideGatewayResponse parse(DecideGatewayRequest request, byte[] payload) {
        JsonNode root;
        try {
            root = objectMapper.readTree(payload);
        } catch (IOException invalidJson) {
            throw schemaViolation("decide worker 响应不是合法 JSON");
        }
        if (root == null || !root.isObject()) {
            throw schemaViolation("decide worker 响应不是 JSON 对象");
        }
        JsonNode schemaNode = root.get("schemaVersion");
        if (schemaNode == null || !schemaNode.isIntegralNumber() || schemaNode.asInt() != 1) {
            throw schemaViolation("decide worker schemaVersion 不匹配");
        }
        JsonNode decisionNode = root.get("decision");
        DecideDecision decision = decisionNode != null && decisionNode.isTextual()
                ? DecideDecision.fromWire(decisionNode.asText()) : null;
        if (decision == null) {
            throw schemaViolation("decide worker decision 未知");
        }

        JsonNode toolCallNode = root.get("toolCall");
        DecideGatewayResponse.ToolCall toolCall = null;
        if (decision == DecideDecision.CALL_TOOL) {
            if (toolCallNode == null || !toolCallNode.isObject()) {
                throw schemaViolation("CALL_TOOL 缺少 toolCall");
            }
            JsonNode nameNode = toolCallNode.get("name");
            if (nameNode == null || !nameNode.isTextual() || nameNode.asText().isBlank()) {
                throw schemaViolation("CALL_TOOL 缺少 toolCall.name");
            }
            JsonNode argumentsNode = toolCallNode.get("arguments");
            if (argumentsNode == null || !argumentsNode.isObject()) {
                throw schemaViolation("CALL_TOOL 的 arguments 不是 JSON object");
            }
            String toolName = nameNode.asText().strip();
            if (!requestToolNames(request).contains(toolName)) {
                throw schemaViolation("CALL_TOOL 的工具不在本次下发的 tools 内");
            }
            toolCall = new DecideGatewayResponse.ToolCall(toolName, argumentsNode.deepCopy());
        } else if (toolCallNode != null && !toolCallNode.isNull()) {
            throw schemaViolation("非 CALL_TOOL 决策不得携带 toolCall");
        }

        String promptVersion = optionalText(root.get("promptVersion"), "promptVersion");
        String modelAlias = optionalText(root.get("modelAlias"), "modelAlias");
        Long latencyMs = optionalLatency(root.get("latencyMs"));
        return new DecideGatewayResponse(1, decision, toolCall, parseUsage(root.get("usage")),
                promptVersion, modelAlias, latencyMs);
    }

    private static Set<String> requestToolNames(DecideGatewayRequest request) {
        Set<String> names = new HashSet<>();
        for (DecideGatewayRequest.Tool tool : request.tools()) {
            if (tool != null && tool.name() != null) {
                names.add(tool.name());
            }
        }
        return names;
    }

    private static DecideGatewayResponse.Usage parseUsage(JsonNode usageNode) {
        if (usageNode == null || usageNode.isNull()) {
            return DecideGatewayResponse.Usage.unknown();
        }
        if (!usageNode.isObject()) {
            throw schemaViolation("decide worker usage 不是 JSON object");
        }
        Integer inputTokens = integerOrNull(usageNode.get("inputTokens"));
        Integer outputTokens = integerOrNull(usageNode.get("outputTokens"));
        String source = "UNKNOWN";
        JsonNode sourceNode = usageNode.get("source");
        if (sourceNode != null && sourceNode.isTextual()) {
            String candidate = sourceNode.asText();
            if ("PROVIDER".equals(candidate) || "ESTIMATE".equals(candidate)
                    || "UNKNOWN".equals(candidate)) {
                source = candidate;
            }
        }
        return new DecideGatewayResponse.Usage(inputTokens, outputTokens, source);
    }

    private static Integer integerOrNull(JsonNode node) {
        return node != null && node.isIntegralNumber() ? node.asInt() : null;
    }

    private static String optionalText(JsonNode node, String field) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isTextual()) {
            throw schemaViolation("decide worker " + field + " 不是字符串");
        }
        return node.asText();
    }

    private static Long optionalLatency(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isIntegralNumber() || node.asLong() < 0) {
            throw schemaViolation("decide worker latencyMs 不是非负整数");
        }
        return node.asLong();
    }

    private static DecideGatewayException schemaViolation(String message) {
        return new DecideGatewayException(DecideGatewayErrorCode.SCHEMA, message);
    }

    private static DecideGatewayException httpFailure(int status) {
        if (status == 408 || status == 504) {
            // worker 的 DEADLINE_EXCEEDED：服务端 deadline 已经耗尽，影子调用直接放弃。
            return new DecideGatewayException(DecideGatewayErrorCode.TIMEOUT,
                    "decide worker 超过 deadline");
        }
        if (status == 429 || status >= 500) {
            return new DecideGatewayException(DecideGatewayErrorCode.UNAVAILABLE,
                    "decide worker 暂不可用");
        }
        return new DecideGatewayException(DecideGatewayErrorCode.HTTP,
                "decide worker 拒绝了请求");
    }

    /**
     * 传输层异常分类（包内可见以便单元测试覆盖超时分支）：
     * 连接/读取超时 → timeout；连接失败等 IO → unavailable；中断 → unexpected。
     */
    static DecideGatewayException classifyTransportFailure(Throwable failure) {
        if (failure instanceof HttpConnectTimeoutException) {
            return new DecideGatewayException(DecideGatewayErrorCode.TIMEOUT,
                    "decide worker 连接超时");
        }
        if (failure instanceof HttpTimeoutException) {
            return new DecideGatewayException(DecideGatewayErrorCode.TIMEOUT,
                    "decide worker 读取超时");
        }
        if (failure instanceof ConnectException) {
            return new DecideGatewayException(DecideGatewayErrorCode.UNAVAILABLE,
                    "decide worker 连接失败");
        }
        if (failure instanceof IOException) {
            return new DecideGatewayException(DecideGatewayErrorCode.UNAVAILABLE,
                    "decide worker 网络异常");
        }
        return new DecideGatewayException(DecideGatewayErrorCode.UNEXPECTED,
                "decide worker 调用异常");
    }

    private static int effectiveDeadlineMs(DecideGatewayRequest request) {
        int deadlineMs = request.budget() == null ? 0 : request.budget().deadlineMs();
        if (deadlineMs < MIN_DEADLINE_MS) {
            return MIN_DEADLINE_MS;
        }
        return Math.min(deadlineMs, MAX_DEADLINE_MS);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
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
}
