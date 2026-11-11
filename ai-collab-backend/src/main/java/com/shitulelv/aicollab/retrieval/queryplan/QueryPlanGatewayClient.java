package com.shitulelv.aicollab.retrieval.queryplan;

import com.fasterxml.jackson.annotation.JsonInclude;
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
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Python query plan worker 的 HTTP 客户端（W4 冻结契约 §4）。
 *
 * <p><b>本波只做契约</b>：没有检索管线调用方，不注入任何检索服务，{@code enabled}
 * 默认 false。禁止在检索路径上引用本类（接线属于后续波次）。</p>
 *
 * <p><b>超时/重试</b>：connect 2s；read {@code deadlineMs + 5s}；<b>不重试</b>
 * （只读建议端点，失败由未来调用方决定是否使用原查询）。</p>
 *
 * <p><b>鉴权</b>：{@code Authorization: Bearer <service-token>}；未配置时不发该头。
 * 服务令牌本波复用 {@code rerank.service-token}，收敛在唯一的私有方法
 * {@link #serviceToken()}，W3.2 之后替换为内部令牌提供者。本端点无幂等键。</p>
 *
 * <p><b>契约校验</b>：{@code filters} 只接受 {@code documentIds}/{@code docTypes}/
 * {@code dateRange} 三类键，未知键按契约违规拒绝；响应字段缺失/越界同样拒绝。
 * 日志不记录 query、过滤值或响应正文。</p>
 */
@Component
public class QueryPlanGatewayClient {

    private static final Logger log = LoggerFactory.getLogger(QueryPlanGatewayClient.class);

    static final String PLAN_PATH = "/v1/rag/plan";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration DEFAULT_READ_TIMEOUT_GRACE = Duration.ofSeconds(5);
    private static final long MAX_RESPONSE_BYTES = 1024L * 1024L;
    private static final int MIN_DEADLINE_MS = 1;
    private static final int MAX_DEADLINE_MS = 30000;
    private static final int MAX_REWRITTEN_QUERY_CHARS = 4000;
    private static final int MAX_SUB_QUERIES = 8;
    private static final int MAX_SUB_QUERY_CHARS = 500;
    private static final int MAX_FILTER_DOCUMENT_IDS = 200;
    private static final int MAX_FILTER_DOCUMENT_ID_CHARS = 128;
    private static final int MAX_FILTER_DOC_TYPES = 10;
    private static final int MAX_FILTER_DOC_TYPE_CHARS = 64;
    private static final Set<String> ALLOWED_FILTER_KEYS =
            Set.of("documentIds", "docTypes", "dateRange");
    private static final Set<String> ALLOWED_DATE_RANGE_KEYS = Set.of("from", "to");

    private final QueryPlanProperties properties;
    private final RerankProperties rerankProperties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final Duration readTimeoutGrace;

    @Autowired
    public QueryPlanGatewayClient(QueryPlanProperties properties, RerankProperties rerankProperties,
            ObjectMapper objectMapper) {
        this(properties, rerankProperties, objectMapper,
                OutboundHttpClients.builder()
                        .connectTimeout(CONNECT_TIMEOUT)
                        .version(HttpClient.Version.HTTP_1_1)
                        .build(),
                DEFAULT_READ_TIMEOUT_GRACE);
    }

    /** 测试可注入自定义 HttpClient 与读取宽限（用短宽限验证读超时，不真等 5s）。 */
    QueryPlanGatewayClient(QueryPlanProperties properties, RerankProperties rerankProperties,
            ObjectMapper objectMapper, HttpClient httpClient, Duration readTimeoutGrace) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.rerankProperties = Objects.requireNonNull(rerankProperties, "rerankProperties");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.readTimeoutGrace = readTimeoutGrace == null || readTimeoutGrace.isNegative()
                ? DEFAULT_READ_TIMEOUT_GRACE : readTimeoutGrace;
    }

    /**
     * 调用 query plan worker。
     *
     * @throws QueryPlanGatewayException 所有失败都转成稳定的原因分类；调用方负责决定是否使用原查询
     */
    public QueryPlanResponse plan(QueryPlanRequest request) {
        Objects.requireNonNull(request, "request");
        int deadlineMs = effectiveDeadlineMs(request);
        String body = serialize(request, deadlineMs);
        long startNanos = System.nanoTime();

        HttpResponse<byte[]> response;
        try {
            response = httpClient.send(buildHttpRequest(body, deadlineMs),
                    HttpResponse.BodyHandlers.ofByteArray());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            log.warn("query plan 调用被中断: elapsedMs={}", elapsedMillis(startNanos));
            throw new QueryPlanGatewayException(QueryPlanGatewayException.Reason.UNEXPECTED,
                    "query plan 调用被中断");
        } catch (IOException transportFailure) {
            QueryPlanGatewayException classified = classifyTransportFailure(transportFailure);
            log.warn("query plan 传输失败: elapsedMs={} reason={}",
                    elapsedMillis(startNanos), classified.reason());
            throw classified;
        }

        int status = response.statusCode();
        byte[] payload = response.body() == null ? new byte[0] : response.body();
        if (status < 200 || status >= 300) {
            QueryPlanGatewayException failure = httpFailure(status);
            log.warn("query plan 返回非 2xx: status={} elapsedMs={} reason={}",
                    status, elapsedMillis(startNanos), failure.reason());
            throw failure;
        }
        if (payload.length > MAX_RESPONSE_BYTES) {
            log.warn("query plan 响应体超过上限: status={} elapsedMs={}",
                    status, elapsedMillis(startNanos));
            throw new QueryPlanGatewayException(QueryPlanGatewayException.Reason.SCHEMA,
                    "query plan 响应体超过上限");
        }
        try {
            QueryPlanResponse parsed = parse(payload);
            log.debug("query plan 调用成功: status={} elapsedMs={}",
                    status, elapsedMillis(startNanos));
            return parsed;
        } catch (QueryPlanGatewayException contractFailure) {
            log.warn("query plan 响应违反契约: status={} elapsedMs={} reason={}",
                    status, elapsedMillis(startNanos), contractFailure.reason());
            throw contractFailure;
        }
    }

    private HttpRequest buildHttpRequest(String body, int deadlineMs) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(properties.workerBaseUrl() + PLAN_PATH))
                .timeout(Duration.ofMillis(deadlineMs + readTimeoutGrace.toMillis()))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        String token = serviceToken();
        if (!token.isEmpty()) {
            builder.header("Authorization", "Bearer " + token);
        }
        return builder.build();
    }

    /**
     * 内部服务令牌的唯一读取点（本波复用 {@code rerank.service-token}）。
     * W3.2 引入内部令牌提供者后只改这里；未配置时返回空串，不发送鉴权头。
     */
    private String serviceToken() {
        String token = rerankProperties.serviceToken();
        return token == null ? "" : token.trim();
    }

    private String serialize(QueryPlanRequest request, int deadlineMs) {
        Payload payload = new Payload(
                1,
                request.query(),
                request.locale(),
                request.historySummary().isBlank() ? null : request.historySummary(),
                request.documentIds(),
                deadlineMs);
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (IOException serializationFailure) {
            throw new QueryPlanGatewayException(QueryPlanGatewayException.Reason.UNEXPECTED,
                    "query plan 请求序列化失败");
        }
    }

    private QueryPlanResponse parse(byte[] payload) {
        JsonNode root;
        try {
            root = objectMapper.readTree(payload);
        } catch (IOException invalidJson) {
            throw schemaViolation("query plan 响应不是合法 JSON");
        }
        if (root == null || !root.isObject()) {
            throw schemaViolation("query plan 响应不是 JSON 对象");
        }
        JsonNode schemaNode = root.get("schemaVersion");
        if (schemaNode == null || !schemaNode.isIntegralNumber() || schemaNode.asInt() != 1) {
            throw schemaViolation("query plan schemaVersion 不匹配");
        }

        JsonNode rewrittenNode = root.get("rewrittenQuery");
        if (rewrittenNode == null || !rewrittenNode.isTextual()) {
            throw schemaViolation("query plan 缺少 rewrittenQuery");
        }
        String rewrittenQuery = rewrittenNode.asText().strip();
        if (rewrittenQuery.isEmpty() || rewrittenQuery.length() > MAX_REWRITTEN_QUERY_CHARS) {
            throw schemaViolation("query plan rewrittenQuery 越界");
        }

        List<String> subQueries = parseSubQueries(root.get("subQueries"));
        QueryPlanResponse.Filters filters = parseFilters(root.get("filters"));

        JsonNode confidenceNode = root.get("confidence");
        if (confidenceNode == null || !confidenceNode.isNumber()) {
            throw schemaViolation("query plan 缺少 confidence");
        }
        double confidence = confidenceNode.asDouble();
        if (!Double.isFinite(confidence) || confidence < 0.0 || confidence > 1.0) {
            throw schemaViolation("query plan confidence 越界");
        }

        return new QueryPlanResponse(1, rewrittenQuery, subQueries, filters, confidence);
    }

    private static List<String> parseSubQueries(JsonNode node) {
        if (node == null || !node.isArray()) {
            throw schemaViolation("query plan 缺少 subQueries");
        }
        if (node.size() > MAX_SUB_QUERIES) {
            throw schemaViolation("query plan subQueries 数量越界");
        }
        Set<String> seen = new HashSet<>();
        List<String> subQueries = new ArrayList<>(node.size());
        for (JsonNode item : node) {
            if (item == null || !item.isTextual()) {
                throw schemaViolation("query plan subQueries 条目不是字符串");
            }
            String subQuery = item.asText().strip();
            if (subQuery.isEmpty() || subQuery.length() > MAX_SUB_QUERY_CHARS) {
                throw schemaViolation("query plan subQueries 条目越界");
            }
            if (!seen.add(subQuery)) {
                throw schemaViolation("query plan subQueries 条目重复");
            }
            subQueries.add(subQuery);
        }
        return List.copyOf(subQueries);
    }

    private static QueryPlanResponse.Filters parseFilters(JsonNode node) {
        if (node == null || node.isNull()) {
            return QueryPlanResponse.Filters.empty();
        }
        if (!node.isObject()) {
            throw schemaViolation("query plan filters 不是 JSON object");
        }
        List<String> documentIds = List.of();
        List<String> docTypes = List.of();
        QueryPlanResponse.DateRange dateRange = QueryPlanResponse.DateRange.empty();
        Iterator<String> names = node.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            if (!ALLOWED_FILTER_KEYS.contains(name)) {
                // extra=forbid：未知键是契约违规，不静默丢弃。
                throw schemaViolation("query plan filters 含未知键");
            }
            JsonNode value = node.get(name);
            switch (name) {
                case "documentIds" -> documentIds = parseStringArray(value,
                        MAX_FILTER_DOCUMENT_IDS, MAX_FILTER_DOCUMENT_ID_CHARS, "filters.documentIds");
                case "docTypes" -> docTypes = parseStringArray(value,
                        MAX_FILTER_DOC_TYPES, MAX_FILTER_DOC_TYPE_CHARS, "filters.docTypes");
                case "dateRange" -> dateRange = parseDateRange(value);
                default -> throw schemaViolation("query plan filters 含未知键");
            }
        }
        return new QueryPlanResponse.Filters(documentIds, docTypes, dateRange);
    }

    private static List<String> parseStringArray(JsonNode node, int maxItems, int maxChars,
            String field) {
        if (node == null || !node.isArray()) {
            throw schemaViolation("query plan " + field + " 不是数组");
        }
        if (node.size() > maxItems) {
            throw schemaViolation("query plan " + field + " 数量越界");
        }
        List<String> values = new ArrayList<>(node.size());
        for (JsonNode item : node) {
            if (item == null || !item.isTextual()) {
                throw schemaViolation("query plan " + field + " 条目不是字符串");
            }
            String value = item.asText().strip();
            if (value.isEmpty() || value.length() > maxChars) {
                throw schemaViolation("query plan " + field + " 条目越界");
            }
            values.add(value);
        }
        return List.copyOf(values);
    }

    private static QueryPlanResponse.DateRange parseDateRange(JsonNode node) {
        if (node == null || node.isNull()) {
            return QueryPlanResponse.DateRange.empty();
        }
        if (!node.isObject()) {
            throw schemaViolation("query plan filters.dateRange 不是 JSON object");
        }
        Iterator<String> names = node.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            if (!ALLOWED_DATE_RANGE_KEYS.contains(name)) {
                throw schemaViolation("query plan filters.dateRange 含未知键");
            }
        }
        LocalDate from = parseDate(node.get("from"));
        LocalDate to = parseDate(node.get("to"));
        if (from != null && to != null && from.isAfter(to)) {
            throw schemaViolation("query plan filters.dateRange from 晚于 to");
        }
        return new QueryPlanResponse.DateRange(from == null ? null : from.toString(),
                to == null ? null : to.toString());
    }

    private static LocalDate parseDate(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isTextual()) {
            throw schemaViolation("query plan filters.dateRange 日期不是字符串");
        }
        String value = node.asText().strip();
        if (value.isEmpty()) {
            throw schemaViolation("query plan filters.dateRange 日期为空");
        }
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException invalidDate) {
            throw schemaViolation("query plan filters.dateRange 日期不是 ISO-8601");
        }
    }

    private static QueryPlanGatewayException schemaViolation(String message) {
        return new QueryPlanGatewayException(QueryPlanGatewayException.Reason.SCHEMA, message);
    }

    private static QueryPlanGatewayException httpFailure(int status) {
        if (status == 408 || status == 504) {
            return new QueryPlanGatewayException(QueryPlanGatewayException.Reason.TIMEOUT,
                    "query plan worker 超过 deadline");
        }
        if (status == 429 || status >= 500) {
            return new QueryPlanGatewayException(QueryPlanGatewayException.Reason.UNAVAILABLE,
                    "query plan worker 暂不可用");
        }
        return new QueryPlanGatewayException(QueryPlanGatewayException.Reason.HTTP,
                "query plan worker 拒绝了请求");
    }

    /** 传输层异常分类（包内可见以便单元测试覆盖超时分支）。 */
    static QueryPlanGatewayException classifyTransportFailure(Throwable failure) {
        if (failure instanceof HttpConnectTimeoutException) {
            return new QueryPlanGatewayException(QueryPlanGatewayException.Reason.TIMEOUT,
                    "query plan worker 连接超时");
        }
        if (failure instanceof HttpTimeoutException) {
            return new QueryPlanGatewayException(QueryPlanGatewayException.Reason.TIMEOUT,
                    "query plan worker 读取超时");
        }
        if (failure instanceof ConnectException) {
            return new QueryPlanGatewayException(QueryPlanGatewayException.Reason.UNAVAILABLE,
                    "query plan worker 连接失败");
        }
        if (failure instanceof IOException) {
            return new QueryPlanGatewayException(QueryPlanGatewayException.Reason.UNAVAILABLE,
                    "query plan worker 网络异常");
        }
        return new QueryPlanGatewayException(QueryPlanGatewayException.Reason.UNEXPECTED,
                "query plan worker 调用异常");
    }

    private int effectiveDeadlineMs(QueryPlanRequest request) {
        Integer requested = request.deadlineMs();
        int deadlineMs = requested == null ? properties.deadlineMs() : requested;
        if (deadlineMs < MIN_DEADLINE_MS) {
            return MIN_DEADLINE_MS;
        }
        return Math.min(deadlineMs, MAX_DEADLINE_MS);
    }

    private static long elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    /** 线上请求体：{@code historySummary} 为空时整体省略。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record Payload(
            int schemaVersion,
            String query,
            String locale,
            String historySummary,
            List<String> documentIds,
            int deadlineMs) {
    }
}
