package com.shitulelv.aicollab.agent.infrastructure.mcp.gateway;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.shitulelv.aicollab.agent.infrastructure.mcp.McpClientFacade;
import com.shitulelv.aicollab.agent.infrastructure.mcp.McpConnectionManager;
import com.shitulelv.aicollab.agent.infrastructure.mcp.McpResultSanitizer;
import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.internal.ai.InternalAiErrorCode;
import com.shitulelv.aicollab.internal.ai.InternalAiErrorResponse;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 内部 MCP Gateway 的 JSON-RPC 处理核心。只有 Controller 条件装配，见
 * {@link McpGatewayConfiguration}。
 */
final class McpGatewayService {

    static final int MAX_REQUEST_BYTES = 256 * 1024;
    private static final int MAX_ARGUMENT_BYTES = 64 * 1024;
    private static final Pattern SHA256_VERSION = Pattern.compile("sha256:[0-9a-f]{64}");
    private static final Pattern AUDIT_TOOL_NAME = Pattern.compile("[A-Za-z0-9_.:-]{1,160}");
    private static final Set<String> AUTHORIZATION_FIELDS = Set.of(
            "projectId", "projectRef", "runId", "runRef", "userId", "userRef",
            "role", "scope", "scopes", "actorId", "actorRef", "tenantId", "approvalId");

    private final McpGatewayProperties properties;
    private final McpGatewayCatalogService catalogService;
    private final McpGatewayAuthorizer authorizer;
    private final McpConnectionManager clients;
    private final McpResultSanitizer resultSanitizer;
    private final McpGatewayAuditor auditor;
    private final ObjectMapper json;

    McpGatewayService(McpGatewayProperties properties, McpGatewayCatalogService catalogService,
            McpGatewayAuthorizer authorizer, McpConnectionManager clients,
            McpResultSanitizer resultSanitizer, McpGatewayAuditor auditor, ObjectMapper json) {
        this.properties = properties;
        this.catalogService = catalogService;
        this.authorizer = authorizer;
        this.clients = clients;
        this.resultSanitizer = resultSanitizer;
        this.auditor = auditor;
        this.json = json;
    }

    McpGatewayResponse handle(String requestBody, Map<String, Object> claims) {
        long started = System.nanoTime();
        int requestBytes = requestBody == null ? 0 : requestBody.getBytes(StandardCharsets.UTF_8).length;
        JsonNode id = null;
        String method = "";
        McpGatewayIdentity identity = null;
        McpGatewayAuthorizer.AuthorizedContext authorized = null;
        String toolName = "";
        String catalogVersion = "";
        String toolSchemaHash = "";
        String connectionCode = "";
        UUID connectionId = null;
        String decision = "DENY";
        String reason = "";
        String outcome = "DENIED";
        int httpStatus = 200;
        ExecutionFence executionFence = new ExecutionFence();
        Object responseBody;
        try {
            if (requestBody == null || requestBytes > MAX_REQUEST_BYTES) {
                throw McpGatewayException.invalidParams();
            }
            JsonNode request;
            try {
                request = json.reader()
                        .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                        .readTree(requestBody);
            } catch (Exception invalid) {
                throw McpGatewayException.invalidParams();
            }
            if (request == null || !request.isObject()) {
                throw McpGatewayException.invalidParams();
            }
            id = validId(request.get("id"));
            rejectSelfReportedAuthorization(request, "$");
            if (!request.path("jsonrpc").isTextual() || !"2.0".equals(request.path("jsonrpc").textValue())) {
                throw McpGatewayException.invalidParams();
            }
            if (id == null) {
                throw McpGatewayException.invalidParams();
            }
            if (!request.path("method").isTextual()) {
                throw McpGatewayException.invalidParams();
            }
            method = request.path("method").textValue();
            identity = McpGatewayIdentity.resolve(claims, properties);
            requireMethodScope(identity, method);
            JsonNode params = request.get("params");
            ObjectNode result = switch (method) {
                case "initialize" -> initialize(params);
                case "ping" -> ping(params);
                case "tools/list" -> {
                    authorized = authorizer.authorize(identity);
                    McpGatewayCatalogService.Catalog catalog = catalogService.build(identity.projectRef());
                    catalogVersion = catalog.catalogVersion();
                    yield list(catalog);
                }
                case "tools/call" -> {
                    CallRequest call = callRequest(params, identity);
                    toolName = auditToolName(call.name());
                    catalogVersion = call.catalogVersion();
                    toolSchemaHash = call.toolSchemaHash();
                    authorized = authorizer.authorize(identity);
                    CallResult callResult = call(identity, call, authorized, requestBytes, executionFence);
                    connectionCode = callResult.connectionCode();
                    connectionId = callResult.connectionId();
                    catalogVersion = callResult.catalogVersion();
                    toolSchemaHash = callResult.toolSchemaHash();
                    outcome = callResult.outcome();
                    decision = "ALLOW";
                    yield callResult.body();
                }
                default -> throw McpGatewayException.unknownMethod();
            };
            responseBody = rpcResult(id, result);
            decision = "ALLOW";
            if (outcome.equals("DENIED")) {
                outcome = "SUCCEEDED";
            }
        } catch (McpGatewayIdentity.McpGatewayHttpAuthException authFailure) {
            httpStatus = 401;
            outcome = "DENIED";
            reason = "UNAUTHORIZED";
            responseBody = InternalAiErrorResponse.of(InternalAiErrorCode.INVALID_REQUEST,
                    "内部服务令牌无效");
        } catch (McpGatewayException gatewayFailure) {
            outcome = gatewayFailure.kind().wireName();
            reason = gatewayFailure.kind().wireName();
            responseBody = rpcError(id, gatewayFailure);
        } catch (RuntimeException unexpected) {
            outcome = McpGatewayException.Kind.INTERNAL.wireName();
            reason = outcome;
            responseBody = rpcError(id, McpGatewayException.business(McpGatewayException.Kind.INTERNAL));
        }
        int responseBytes = bytes(responseBody);
        long latencyMs = Math.max(0, (System.nanoTime() - started) / 1_000_000);
        McpGatewayAuditor.Event auditEvent = new McpGatewayAuditor.Event(
                Instant.now(),
                auditMethod(method),
                authorized == null ? "SERVICE" : authorized.actorType(),
                identity == null ? null : identity.runRef(),
                identity == null ? null : identity.projectRef(),
                identity == null ? null : identity.runRef(),
                authorized == null ? null : authorized.requesterId(),
                connectionId,
                connectionCode,
                toolName,
                catalogVersion,
                toolSchemaHash,
                decision,
                reason,
                latencyMs,
                requestBytes,
                responseBytes,
                outcome);
        try {
            auditor.record(auditEvent);
        } catch (RuntimeException auditUnavailable) {
            McpGatewayException.Kind kind = executionFence.externalAttempted
                    ? McpGatewayException.Kind.OUTCOME_UNKNOWN
                    : McpGatewayException.Kind.INTERNAL;
            responseBody = rpcError(id, McpGatewayException.business(kind));
            outcome = kind.wireName();
            reason = "AUDIT_UNAVAILABLE";
            decision = "DENY";
            responseBytes = bytes(responseBody);
            latencyMs = Math.max(0, (System.nanoTime() - started) / 1_000_000);
            auditor.logOnly(new McpGatewayAuditor.Event(
                    Instant.now(),
                    auditMethod(method),
                    authorized == null ? "SERVICE" : authorized.actorType(),
                    identity == null ? null : identity.runRef(),
                    identity == null ? null : identity.projectRef(),
                    identity == null ? null : identity.runRef(),
                    authorized == null ? null : authorized.requesterId(),
                    connectionId,
                    connectionCode,
                    toolName,
                    catalogVersion,
                    toolSchemaHash,
                    decision,
                    reason,
                    latencyMs,
                    requestBytes,
                    responseBytes,
                    outcome));
        }
        return new McpGatewayResponse(httpStatus, responseBody);
    }

    private ObjectNode initialize(JsonNode params) {
        JsonNode object = requireObject(params);
        requireSchemaVersion(object);
        if (!object.path("protocolVersion").isTextual()
                || !"2025-03-26".equals(object.path("protocolVersion").textValue())) {
            throw McpGatewayException.invalidParams();
        }
        JsonNode client = object.get("client");
        if (client == null || !client.isObject()
                || !validText(client.get("name"), 128)
                || !validText(client.get("version"), 64)) {
            throw McpGatewayException.invalidParams();
        }
        ObjectNode result = json.createObjectNode();
        result.put("schemaVersion", 1);
        result.put("protocolVersion", "2025-03-26");
        ObjectNode serverInfo = result.putObject("serverInfo");
        serverInfo.put("name", "ai-collab-mcp-gateway");
        serverInfo.put("version", "p1");
        result.putObject("capabilities").putObject("tools").put("listChanged", false);
        return result;
    }

    private ObjectNode ping(JsonNode params) {
        requireSchemaVersion(requireObject(params));
        ObjectNode result = json.createObjectNode();
        result.put("schemaVersion", 1);
        result.put("status", "ok");
        return result;
    }

    private ObjectNode list(McpGatewayCatalogService.Catalog catalog) {
        ObjectNode result = json.createObjectNode();
        result.put("schemaVersion", 1);
        result.put("catalogVersion", catalog.catalogVersion());
        var tools = result.putArray("tools");
        for (McpGatewayCatalogService.Tool tool : catalog.tools()) {
            ObjectNode item = tools.addObject();
            item.put("name", tool.name());
            item.put("description", tool.description());
            item.set("inputSchema", tool.inputSchema());
            item.put("toolSchemaHash", tool.toolSchemaHash());
            item.putObject("annotations").put("readOnlyHint", true);
        }
        return result;
    }

    private CallRequest callRequest(JsonNode params, McpGatewayIdentity identity) {
        JsonNode object = requireObject(params);
        requireSchemaVersion(object);
        if (!validText(object.get("name"), 160)
                || !validText(object.get("catalogVersion"), 71)
                || !validText(object.get("toolSchemaHash"), 71)
                || !validText(object.get("callId"), 128)
                || !object.has("arguments") || !object.get("arguments").isObject()) {
            throw McpGatewayException.invalidParams();
        }
        String catalogVersion = object.path("catalogVersion").textValue();
        String toolSchemaHash = object.path("toolSchemaHash").textValue();
        if (!SHA256_VERSION.matcher(catalogVersion).matches()
                || !SHA256_VERSION.matcher(toolSchemaHash).matches()) {
            throw McpGatewayException.business(McpGatewayException.Kind.PARAM_INVALID);
        }
        JsonNode arguments = object.get("arguments");
        if (argumentsBytes(arguments) > MAX_ARGUMENT_BYTES) {
            throw McpGatewayException.business(McpGatewayException.Kind.PARAM_INVALID);
        }
        if (identity.catalogVersion() == null) {
            throw McpGatewayException.business(McpGatewayException.Kind.SCHEMA_DRIFT);
        }
        return new CallRequest(object.path("name").textValue(), catalogVersion,
                toolSchemaHash, arguments);
    }

    private CallResult call(McpGatewayIdentity identity, CallRequest request,
            McpGatewayAuthorizer.AuthorizedContext authorized, int requestBytes,
            ExecutionFence executionFence) {
        McpGatewayCatalogService.Catalog before = catalogService.build(identity.projectRef());
        if (!identity.catalogVersion().equals(before.catalogVersion())
                || !request.catalogVersion().equals(before.catalogVersion())) {
            throw McpGatewayException.business(McpGatewayException.Kind.SCHEMA_DRIFT);
        }
        McpGatewayCatalogService.Tool tool = before.tool(request.name())
                .orElseThrow(() -> McpGatewayException.business(McpGatewayException.Kind.TOOL_DISABLED));
        if (!tool.toolSchemaHash().equals(request.toolSchemaHash())) {
            throw McpGatewayException.business(McpGatewayException.Kind.SCHEMA_DRIFT);
        }
        if (!McpJsonSchemaValidator.valid(tool.inputSchema(), request.arguments())) {
            throw McpGatewayException.business(McpGatewayException.Kind.PARAM_INVALID);
        }
        try {
            if (!authorizer.stillAuthorized(identity)) {
                throw McpGatewayException.business(McpGatewayException.Kind.TOOL_REVOKED);
            }
        } catch (McpGatewayException gatewayFailure) {
            throw gatewayFailure;
        } catch (RuntimeException unavailable) {
            throw McpGatewayException.business(McpGatewayException.Kind.NOT_EXECUTED);
        }

        auditAdmission(identity, authorized, tool, request, requestBytes);
        executionFence.externalAttempted = true;
        long started = System.nanoTime();
        McpClientFacade.CallResult raw;
        try {
            raw = clients.requireClient(tool.connection()).callTool(tool.serverName(), request.arguments(),
                    Duration.ofMillis(tool.connection().timeoutMs()));
        } catch (RuntimeException failure) {
            throwAfterCallIfRevoked(identity);
            throw McpGatewayException.business(mapFailure(failure));
        }

        throwAfterCallIfRevoked(identity);
        McpGatewayCatalogService.Catalog after;
        try {
            after = catalogService.build(identity.projectRef());
        } catch (RuntimeException unavailable) {
            throw McpGatewayException.business(McpGatewayException.Kind.OUTCOME_UNKNOWN);
        }
        McpGatewayCatalogService.Tool current = after.tool(request.name()).orElse(null);
        if (!before.catalogVersion().equals(after.catalogVersion())
                || current == null
                || !current.toolSchemaHash().equals(request.toolSchemaHash())
                || current.bindingVersion() != tool.bindingVersion()) {
            throw McpGatewayException.business(McpGatewayException.Kind.TOOL_REVOKED);
        }
        if (raw.error()) {
            throw McpGatewayException.business(McpGatewayException.Kind.OUTCOME_UNKNOWN);
        }

        JsonNode source = raw.structuredContent() != null && !raw.structuredContent().isNull()
                ? raw.structuredContent() : raw.content();
        if (source == null || source.isMissingNode() || source.isNull()) {
            throw McpGatewayException.business(McpGatewayException.Kind.OUTCOME_UNKNOWN);
        }
        ObjectNode sanitized;
        try {
            sanitized = resultSanitizer.sanitize(source, tool.connection().maxResultBytes());
        } catch (RuntimeException unavailable) {
            throw McpGatewayException.business(McpGatewayException.Kind.OUTCOME_UNKNOWN);
        }
        boolean truncated = sanitized.path("truncated").asBoolean(false);
        JsonNode data = truncated ? truncatedData(sanitized) : sanitized.get("data");
        if (data == null || data.isMissingNode() || data.isNull()) {
            throw McpGatewayException.business(McpGatewayException.Kind.OUTCOME_UNKNOWN);
        }
        ObjectNode body = json.createObjectNode();
        body.put("schemaVersion", 1);
        body.put("catalogVersion", before.catalogVersion());
        body.put("outcome", truncated ? "TRUNCATED" : "SUCCEEDED");
        body.put("untrusted", true);
        body.set("data", data.deepCopy());
        body.put("truncated", truncated);
        body.put("latencyMs", Math.max(0, (System.nanoTime() - started) / 1_000_000));
        return new CallResult(body, truncated ? "TRUNCATED" : "SUCCEEDED",
                before.catalogVersion(), request.toolSchemaHash(),
                tool.connectionCode(), tool.connectionId());
    }

    private void auditAdmission(McpGatewayIdentity identity,
            McpGatewayAuthorizer.AuthorizedContext authorized,
            McpGatewayCatalogService.Tool tool, CallRequest request, int requestBytes) {
        try {
            auditor.record(new McpGatewayAuditor.Event(
                    Instant.now(),
                    "tools/call",
                    authorized.actorType(),
                    identity.runRef(),
                    identity.projectRef(),
                    identity.runRef(),
                    authorized.requesterId(),
                    tool.connectionId(),
                    tool.connectionCode(),
                    tool.name(),
                    request.catalogVersion(),
                    request.toolSchemaHash(),
                    "ALLOW",
                    "ADMISSION",
                    0,
                    requestBytes,
                    0,
                    "ADMITTED"));
        } catch (RuntimeException auditUnavailable) {
            throw McpGatewayException.business(McpGatewayException.Kind.INTERNAL);
        }
    }

    private JsonNode truncatedData(ObjectNode sanitized) {
        if (sanitized.has("preview")) {
            ObjectNode preview = json.createObjectNode();
            preview.set("preview", sanitized.get("preview").deepCopy());
            return preview;
        }
        JsonNode data = sanitized.get("data");
        return data == null ? null : data.deepCopy();
    }

    private McpGatewayException.Kind mapFailure(RuntimeException failure) {
        if (!(failure instanceof BusinessException business)) {
            return McpGatewayException.Kind.INTERNAL;
        }
        return switch (business.getErrorCode()) {
            case AGENT_MCP_RATE_LIMITED -> McpGatewayException.Kind.RATE_LIMITED;
            case AGENT_TOOL_RESULT_TOO_LARGE -> McpGatewayException.Kind.TRUNCATED;
            case AGENT_MCP_ENDPOINT_FORBIDDEN -> McpGatewayException.Kind.NOT_EXECUTED;
            case AGENT_MCP_CONNECTION_DISABLED -> McpGatewayException.Kind.TOOL_REVOKED;
            case AGENT_MCP_SCHEMA_CHANGED -> McpGatewayException.Kind.SCHEMA_DRIFT;
            case AGENT_MCP_DATA_INVALID, AGENT_MCP_CONNECTION_NOT_FOUND ->
                    McpGatewayException.Kind.INTERNAL;
            case AGENT_MCP_TIMEOUT, AGENT_MCP_REDIRECT_BLOCKED,
                    AI_PROVIDER_UNAVAILABLE, AI_PROVIDER_ERROR ->
                    McpGatewayException.Kind.OUTCOME_UNKNOWN;
            default -> McpGatewayException.Kind.OUTCOME_UNKNOWN;
        };
    }

    private void throwAfterCallIfRevoked(McpGatewayIdentity identity) {
        try {
            if (!authorizer.stillAuthorized(identity)) {
                throw McpGatewayException.business(McpGatewayException.Kind.TOOL_REVOKED);
            }
        } catch (McpGatewayException gatewayFailure) {
            throw gatewayFailure;
        } catch (RuntimeException unavailable) {
            throw McpGatewayException.business(McpGatewayException.Kind.OUTCOME_UNKNOWN);
        }
    }

    private void requireMethodScope(McpGatewayIdentity identity, String method) {
        if ("tools/call".equals(method)) {
            if (!identity.canCall()) {
                throw new McpGatewayIdentity.McpGatewayHttpAuthException();
            }
            return;
        }
        if (!identity.canList()) {
            throw new McpGatewayIdentity.McpGatewayHttpAuthException();
        }
    }

    private JsonNode requireObject(JsonNode params) {
        if (params == null || !params.isObject()) {
            throw McpGatewayException.invalidParams();
        }
        return params;
    }

    private static void requireSchemaVersion(JsonNode params) {
        JsonNode version = params.get("schemaVersion");
        if (version == null || !version.isIntegralNumber() || version.asInt() != 1) {
            throw McpGatewayException.invalidParams();
        }
    }

    private static boolean validText(JsonNode node, int maxLength) {
        return node != null && node.isTextual() && !node.textValue().isBlank()
                && node.textValue().length() <= maxLength;
    }

    private JsonNode validId(JsonNode value) {
        if (value == null) {
            return null;
        }
        if (value.isTextual()) {
            String id = value.textValue();
            return id.isEmpty() || id.length() > 128 ? null : value;
        }
        if (value.isIntegralNumber()) {
            return value;
        }
        return null;
    }

    private void rejectSelfReportedAuthorization(JsonNode node, String path) {
        if (node == null) {
            return;
        }
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                if (AUTHORIZATION_FIELDS.contains(field.getKey())) {
                    throw McpGatewayException.invalidParams();
                }
                rejectSelfReportedAuthorization(field.getValue(), path + "." + field.getKey());
            }
            return;
        }
        if (node.isArray()) {
            for (JsonNode item : node) {
                rejectSelfReportedAuthorization(item, path + "[]");
            }
        }
    }

    private int argumentsBytes(JsonNode arguments) {
        try {
            return json.writeValueAsBytes(arguments).length;
        } catch (Exception invalid) {
            throw McpGatewayException.business(McpGatewayException.Kind.PARAM_INVALID);
        }
    }

    private static String auditMethod(String method) {
        return switch (method) {
            case "initialize", "ping", "tools/list", "tools/call" -> method;
            default -> "unknown";
        };
    }

    private static String auditToolName(String name) {
        return name != null && AUDIT_TOOL_NAME.matcher(name).matches() ? name : "[INVALID]";
    }

    private ObjectNode rpcError(JsonNode id, McpGatewayException failure) {
        ObjectNode response = json.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.set("id", id == null ? json.nullNode() : id.deepCopy());
        ObjectNode error = response.putObject("error");
        error.put("code", failure.jsonRpcCode());
        error.put("message", failure.getMessage());
        ObjectNode data = error.putObject("data");
        data.put("kind", failure.kind().wireName());
        data.put("retryable", failure.kind().retryable());
        return response;
    }

    private ObjectNode rpcResult(JsonNode id, ObjectNode result) {
        ObjectNode response = json.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.set("id", id == null ? json.nullNode() : id.deepCopy());
        response.set("result", result);
        return response;
    }

    private int bytes(Object body) {
        try {
            return json.writeValueAsBytes(body).length;
        } catch (Exception invalid) {
            return 0;
        }
    }

    record McpGatewayResponse(int httpStatus, Object body) {
    }

    private record CallRequest(String name, String catalogVersion, String toolSchemaHash,
            JsonNode arguments) {
    }

    private record CallResult(ObjectNode body, String outcome, String catalogVersion,
            String toolSchemaHash, String connectionCode, UUID connectionId) {
    }

    private static final class ExecutionFence {
        private boolean externalAttempted;
    }
}
