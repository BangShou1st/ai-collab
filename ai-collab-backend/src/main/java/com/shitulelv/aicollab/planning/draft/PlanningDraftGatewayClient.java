package com.shitulelv.aicollab.planning.draft;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.common.net.OutboundHttpClients;
import com.shitulelv.aicollab.internal.ai.token.InternalTokenProvider;
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
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Python planning draft worker 的 HTTP 客户端（W5 冻结 §2）。
 *
 * <p><b>超时</b>：connect 2s；read {@code deadlineMs + 5s}（服务端硬 deadline 之外的宽限，
 * 用于拿到 worker 明确返回的 504/422，而不是把 worker 的明确失败误判成网络故障）。</p>
 *
 * <p><b>重试</b>：<b>不重试</b>。影子调用不得放大延迟；失败只记指标。</p>
 *
 * <p><b>鉴权</b>：{@code Authorization: Bearer <InternalTokenProvider.token()>}；
 * 未配置时不发该头，由 worker 侧 fail closed。取令牌收敛在唯一的私有方法
 * {@link #serviceToken()}，调用方无感。</p>
 *
 * <p><b>响应契约</b>：严格校验 schemaVersion=1、{@code plan} 为对象且含 milestones/tasks 数组、
 * {@code warnings} 为 0–20 条短字符串、{@code usage} 为对象或缺省。plan 内部的业务校验
 * 不在本类做——由 {@link PlanningDraftParser} + {@code TaskPlanDraftValidator} 重跑。</p>
 *
 * <p><b>日志红线</b>：只记录 HTTP 状态、耗时与失败原因；<b>绝不</b>记录 goal、项目事实、
 * 响应正文或服务令牌。</p>
 */
@Component
public class PlanningDraftGatewayClient {

    private static final Logger log = LoggerFactory.getLogger(PlanningDraftGatewayClient.class);

    static final String PLANNING_DRAFT_PATH = "/v1/planning/draft";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration DEFAULT_READ_TIMEOUT_GRACE = Duration.ofSeconds(5);
    private static final long MAX_RESPONSE_BYTES = 2L * 1024L * 1024L;
    private static final int MIN_DEADLINE_MS = 1;
    /** 与 Python 契约上限、Java 内部模型网关 MAX_DEADLINE_MS 保持一致（30000）。 */
    private static final int MAX_DEADLINE_MS = 30_000;
    private static final int MAX_WARNINGS = 20;
    private static final int MAX_WARNING_CHARS = 500;

    private final PlanningDraftProperties properties;
    private final InternalTokenProvider tokenProvider;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final Duration readTimeoutGrace;

    @Autowired
    public PlanningDraftGatewayClient(PlanningDraftProperties properties,
            InternalTokenProvider tokenProvider, ObjectMapper objectMapper) {
        this(properties, tokenProvider, objectMapper,
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
    PlanningDraftGatewayClient(PlanningDraftProperties properties,
            InternalTokenProvider tokenProvider, ObjectMapper objectMapper,
            HttpClient httpClient, Duration readTimeoutGrace) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.tokenProvider = Objects.requireNonNull(tokenProvider, "tokenProvider");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.readTimeoutGrace = readTimeoutGrace == null || readTimeoutGrace.isNegative()
                ? DEFAULT_READ_TIMEOUT_GRACE : readTimeoutGrace;
    }

    /**
     * 调用 planning draft worker。
     *
     * @throws PlanningDraftGatewayException 所有失败都转成稳定的原因分类；调用方只记指标，不重试
     */
    public PlanningDraftResponse draft(PlanningDraftRequest request) {
        Objects.requireNonNull(request, "request");
        String body = serialize(request);
        long startNanos = System.nanoTime();

        HttpResponse<byte[]> response;
        try {
            response = httpClient.send(buildHttpRequest(request, body),
                    HttpResponse.BodyHandlers.ofByteArray());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            log.warn("planning draft worker 调用被中断: elapsedMs={} reason={}",
                    elapsedMillis(startNanos), PlanningDraftGatewayErrorCode.UNEXPECTED.tag());
            throw new PlanningDraftGatewayException(PlanningDraftGatewayErrorCode.UNEXPECTED,
                    "planning draft worker 调用被中断");
        } catch (IOException transportFailure) {
            PlanningDraftGatewayException classified = classifyTransportFailure(transportFailure);
            log.warn("planning draft worker 传输失败: elapsedMs={} reason={}",
                    elapsedMillis(startNanos), classified.reason().tag());
            throw classified;
        }

        int status = response.statusCode();
        byte[] payload = response.body() == null ? new byte[0] : response.body();
        if (status < 200 || status >= 300) {
            PlanningDraftGatewayException failure = httpFailure(status);
            log.warn("planning draft worker 返回非 2xx: status={} elapsedMs={} reason={}",
                    status, elapsedMillis(startNanos), failure.reason().tag());
            throw failure;
        }
        if (payload.length > MAX_RESPONSE_BYTES) {
            log.warn("planning draft worker 响应体超过上限: status={} elapsedMs={} reason={}",
                    status, elapsedMillis(startNanos), PlanningDraftGatewayErrorCode.SCHEMA.tag());
            throw new PlanningDraftGatewayException(PlanningDraftGatewayErrorCode.SCHEMA,
                    "planning draft worker 响应体超过上限");
        }

        PlanningDraftResponse parsed;
        try {
            parsed = parse(payload);
        } catch (PlanningDraftGatewayException contractFailure) {
            log.warn("planning draft worker 响应违反契约: status={} elapsedMs={} reason={}",
                    status, elapsedMillis(startNanos), contractFailure.reason().tag());
            throw contractFailure;
        }
        log.debug("planning draft worker 调用成功: status={} elapsedMs={}",
                status, elapsedMillis(startNanos));
        return parsed;
    }

    private HttpRequest buildHttpRequest(PlanningDraftRequest request, String body) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(properties.workerBaseUrl() + PLANNING_DRAFT_PATH))
                .timeout(Duration.ofMillis(effectiveDeadlineMs(request) + readTimeoutGrace.toMillis()))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        String token = serviceToken();
        if (!token.isEmpty()) {
            builder.header("Authorization", "Bearer " + token);
        }
        return builder.build();
    }

    /** 内部服务令牌的唯一读取点；未配置时返回空串，不发送鉴权头。 */
    private String serviceToken() {
        String token = tokenProvider.token();
        return token == null ? "" : token.trim();
    }

    private String serialize(PlanningDraftRequest request) {
        try {
            return objectMapper.writeValueAsString(request);
        } catch (IOException serializationFailure) {
            throw new PlanningDraftGatewayException(PlanningDraftGatewayErrorCode.UNEXPECTED,
                    "planning draft 请求序列化失败");
        }
    }

    private PlanningDraftResponse parse(byte[] payload) {
        JsonNode root;
        try {
            root = objectMapper.readTree(payload);
        } catch (IOException invalidJson) {
            throw schemaViolation("planning draft worker 响应不是合法 JSON");
        }
        if (root == null || !root.isObject()) {
            throw schemaViolation("planning draft worker 响应不是 JSON 对象");
        }
        JsonNode schemaNode = root.get("schemaVersion");
        if (schemaNode == null || !schemaNode.isIntegralNumber() || schemaNode.asInt() != 1) {
            throw schemaViolation("planning draft worker schemaVersion 不匹配");
        }
        JsonNode planNode = root.get("plan");
        if (planNode == null || !planNode.isObject()) {
            throw schemaViolation("planning draft worker 缺少 plan 对象");
        }
        if (!planNode.path("milestones").isArray() || !planNode.path("tasks").isArray()) {
            throw schemaViolation("planning draft worker plan 缺少 milestones/tasks 数组");
        }
        List<String> warnings = parseWarnings(root.get("warnings"));
        return new PlanningDraftResponse(1, planNode.deepCopy(), warnings,
                parseUsage(root.get("usage")));
    }

    private static List<String> parseWarnings(JsonNode warningsNode) {
        if (warningsNode == null || warningsNode.isNull()) {
            return List.of();
        }
        if (!warningsNode.isArray()) {
            throw schemaViolation("planning draft worker warnings 不是数组");
        }
        if (warningsNode.size() > MAX_WARNINGS) {
            throw schemaViolation("planning draft worker warnings 超过 20 条");
        }
        List<String> warnings = new ArrayList<>(warningsNode.size());
        for (JsonNode item : warningsNode) {
            if (item == null || !item.isTextual() || item.asText().isBlank()
                    || item.asText().length() > MAX_WARNING_CHARS) {
                throw schemaViolation("planning draft worker warning 不是 1–500 字符的字符串");
            }
            warnings.add(item.asText());
        }
        return List.copyOf(warnings);
    }

    private static PlanningDraftResponse.Usage parseUsage(JsonNode usageNode) {
        if (usageNode == null || usageNode.isNull()) {
            return PlanningDraftResponse.Usage.unknown();
        }
        if (!usageNode.isObject()) {
            throw schemaViolation("planning draft worker usage 不是 JSON object");
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
        return new PlanningDraftResponse.Usage(inputTokens, outputTokens, source);
    }

    private static Integer integerOrNull(JsonNode node) {
        return node != null && node.isIntegralNumber() ? node.asInt() : null;
    }

    private static PlanningDraftGatewayException schemaViolation(String message) {
        return new PlanningDraftGatewayException(PlanningDraftGatewayErrorCode.SCHEMA, message);
    }

    private static PlanningDraftGatewayException httpFailure(int status) {
        if (status == 408 || status == 504) {
            return new PlanningDraftGatewayException(PlanningDraftGatewayErrorCode.TIMEOUT,
                    "planning draft worker 超过 deadline");
        }
        if (status == 429 || status >= 500) {
            return new PlanningDraftGatewayException(PlanningDraftGatewayErrorCode.UNAVAILABLE,
                    "planning draft worker 暂不可用");
        }
        return new PlanningDraftGatewayException(PlanningDraftGatewayErrorCode.HTTP,
                "planning draft worker 拒绝了请求");
    }

    /**
     * 传输层异常分类（包内可见以便单元测试覆盖超时分支）：
     * 连接/读取超时 → timeout；连接失败等 IO → unavailable；中断 → unexpected。
     */
    static PlanningDraftGatewayException classifyTransportFailure(Throwable failure) {
        if (failure instanceof HttpConnectTimeoutException) {
            return new PlanningDraftGatewayException(PlanningDraftGatewayErrorCode.TIMEOUT,
                    "planning draft worker 连接超时");
        }
        if (failure instanceof HttpTimeoutException) {
            return new PlanningDraftGatewayException(PlanningDraftGatewayErrorCode.TIMEOUT,
                    "planning draft worker 读取超时");
        }
        if (failure instanceof ConnectException) {
            return new PlanningDraftGatewayException(PlanningDraftGatewayErrorCode.UNAVAILABLE,
                    "planning draft worker 连接失败");
        }
        if (failure instanceof IOException) {
            return new PlanningDraftGatewayException(PlanningDraftGatewayErrorCode.UNAVAILABLE,
                    "planning draft worker 网络异常");
        }
        return new PlanningDraftGatewayException(PlanningDraftGatewayErrorCode.UNEXPECTED,
                "planning draft worker 调用异常");
    }

    private static int effectiveDeadlineMs(PlanningDraftRequest request) {
        int deadlineMs = request.deadlineMs();
        if (deadlineMs < MIN_DEADLINE_MS) {
            return MIN_DEADLINE_MS;
        }
        return Math.min(deadlineMs, MAX_DEADLINE_MS);
    }

    private static long elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }
}
