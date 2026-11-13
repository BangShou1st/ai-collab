package com.shitulelv.aicollab.agent.infrastructure.mcp.gateway;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.shitulelv.aicollab.agent.infrastructure.mcp.McpAuthType;
import com.shitulelv.aicollab.agent.infrastructure.mcp.McpClientFacade;
import com.shitulelv.aicollab.agent.infrastructure.mcp.McpConnection;
import com.shitulelv.aicollab.agent.infrastructure.mcp.McpConnectionManager;
import com.shitulelv.aicollab.agent.infrastructure.mcp.McpResultSanitizer;
import com.shitulelv.aicollab.agent.infrastructure.mcp.McpTransport;
import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.project.application.service.AuditService;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class McpGatewayServiceTest {

    private static final String TOOL_HASH = "sha256:" + "b".repeat(64);

    private final ObjectMapper json = new ObjectMapper();
    private final UUID projectId = UUID.randomUUID();
    private final UUID runId = UUID.randomUUID();
    private final UUID requesterId = UUID.randomUUID();
    private final McpConnection connection = connection();
    private final McpGatewayCatalogService.Tool tool = tool();
    private final McpGatewayCatalogService.Catalog catalog = McpGatewayCatalogService.Catalog.of(List.of(tool));

    @Test
    void initializeAndPingFollowFrozenEnvelope() {
        Fixture fixture = fixture("mcp:list mcp:call", new McpGatewayAuditor(null));
        ObjectNode initializeParams = json.createObjectNode();
        initializeParams.put("schemaVersion", 1);
        initializeParams.put("protocolVersion", "2025-03-26");
        initializeParams.putObject("client").put("name", "ai-collab-ai-worker").put("version", "0.1.0");

        JsonNode initialized = body(fixture.service.handle(request("initialize", initializeParams), fixture.claims));
        assertThat(initialized.path("result").path("serverInfo").path("name").asText())
                .isEqualTo("ai-collab-mcp-gateway");
        assertThat(initialized.path("result").path("capabilities").path("tools").path("listChanged").asBoolean())
                .isFalse();

        ObjectNode pingParams = json.createObjectNode().put("schemaVersion", 1);
        JsonNode pinged = body(fixture.service.handle(request("ping", pingParams), fixture.claims));
        assertThat(pinged.path("result").path("status").asText()).isEqualTo("ok");
    }

    @Test
    void toolsListUsesTrustedProjectAndExposesOnlySanitizedCatalog() {
        Fixture fixture = fixture("mcp:list");
        when(fixture.catalogService.build(projectId)).thenReturn(catalog);
        when(fixture.authorizer.authorize(any())).thenReturn(authorized());

        JsonNode response = body(fixture.service.handle(request("tools/list",
                json.createObjectNode().put("schemaVersion", 1)), fixture.claims));

        assertThat(response.path("result").path("catalogVersion").asText()).isEqualTo(catalog.catalogVersion());
        assertThat(response.path("result").path("tools").get(0).path("name").asText())
                .isEqualTo("mcp.local_readonly.echo_text");
        assertThat(response.path("result").path("tools").get(0).path("annotations").path("readOnlyHint").asBoolean())
                .isTrue();
    }

    @Test
    void toolsCallReturnsOnlySanitizedUntrustedData() {
        Fixture fixture = fixture("mcp:list mcp:call");
        when(fixture.catalogService.build(projectId)).thenReturn(catalog);
        when(fixture.authorizer.authorize(any())).thenReturn(authorized());
        when(fixture.authorizer.stillAuthorized(any())).thenReturn(true);
        McpClientFacade client = mock(McpClientFacade.class);
        when(fixture.clients.requireClient(any())).thenReturn(client);
        ObjectNode structured = json.createObjectNode().put("text", "hello");
        when(client.callTool(anyString(), any(), any())).thenReturn(
                new McpClientFacade.CallResult(false, null, structured));

        JsonNode response = body(fixture.service.handle(callRequest("hello"), fixture.claims));

        assertThat(response.path("result").path("outcome").asText()).isEqualTo("SUCCEEDED");
        assertThat(response.path("result").path("untrusted").asBoolean()).isTrue();
        assertThat(response.path("result").path("data").path("text").asText()).isEqualTo("hello");
        assertThat(response.path("result").path("truncated").asBoolean()).isFalse();
    }

    @Test
    void oversizedResultIsExplicitlyTruncatedAndNeverClaimedComplete() {
        Fixture fixture = fixture("mcp:list mcp:call");
        when(fixture.catalogService.build(projectId)).thenReturn(catalog);
        when(fixture.authorizer.authorize(any())).thenReturn(authorized());
        when(fixture.authorizer.stillAuthorized(any())).thenReturn(true);
        McpClientFacade client = mock(McpClientFacade.class);
        when(fixture.clients.requireClient(any())).thenReturn(client);
        when(client.callTool(anyString(), any(), any())).thenReturn(
                new McpClientFacade.CallResult(false, null,
                        json.createObjectNode().put("text", "x".repeat(70_000))));

        JsonNode response = body(fixture.service.handle(callRequest("hello"), fixture.claims));

        assertThat(response.path("result").path("outcome").asText()).isEqualTo("TRUNCATED");
        assertThat(response.path("result").path("truncated").asBoolean()).isTrue();
        assertThat(response.path("result").path("data").has("preview")).isTrue();
        assertThat(response.path("result").path("data").has("text")).isFalse();
    }

    @Test
    void rejectsSelfReportedAuthorizationFieldsWithoutCallingTools() {
        Fixture fixture = fixture("mcp:list mcp:call");
        ObjectNode params = callParams();
        params.put("projectRef", projectId.toString());

        JsonNode response = body(fixture.service.handle(request("tools/call", params), fixture.claims));

        assertThat(response.path("error").path("code").asInt()).isEqualTo(-32602);
        assertThat(response.path("error").path("data").path("kind").asText()).isEqualTo("PARAM_INVALID");
        verifyNoInteractions(fixture.catalogService, fixture.authorizer, fixture.clients);
    }

    @Test
    void toolsCallRequiresMcpCallScopeAndFailsAsHttpAuthError() {
        Fixture fixture = fixture("mcp:list");

        McpGatewayService.McpGatewayResponse response =
                fixture.service.handle(callRequest("hello"), fixture.claims);

        assertThat(response.httpStatus()).isEqualTo(401);
        assertThat(response.body()).isNotInstanceOf(JsonNode.class);
        verifyNoInteractions(fixture.catalogService, fixture.authorizer, fixture.clients);
    }

    @Test
    void missingTrustedClaimsAndMissingStaticBindingFailClosed() {
        Fixture fixture = fixture("mcp:list mcp:call");

        McpGatewayService.McpGatewayResponse missingClaims = fixture.service.handle(request("ping",
                json.createObjectNode().put("schemaVersion", 1)), Map.of());

        assertThat(missingClaims.httpStatus()).isEqualTo(401);
        verifyNoInteractions(fixture.catalogService, fixture.authorizer, fixture.clients);
    }

    @Test
    void mismatchedCatalogOrToolHashReturnsSchemaDrift() {
        Fixture fixture = fixture("mcp:list mcp:call");
        when(fixture.catalogService.build(projectId)).thenReturn(catalog);
        when(fixture.authorizer.authorize(any())).thenReturn(authorized());
        ObjectNode params = callParams();
        params.put("toolSchemaHash", "sha256:" + "c".repeat(64));
        params.set("arguments", json.createObjectNode().put("text", "hello"));

        JsonNode response = body(fixture.service.handle(request("tools/call", params), fixture.claims));

        assertThat(response.path("error").path("data").path("kind").asText()).isEqualTo("SCHEMA_DRIFT");
        verifyNoInteractions(fixture.clients);
    }

    @Test
    void missingCatalogClaimUnknownToolAndSchemaViolationAreRejectedBeforeExecution() {
        Fixture fixture = fixture("mcp:list mcp:call");
        when(fixture.catalogService.build(projectId)).thenReturn(catalog);
        when(fixture.authorizer.authorize(any())).thenReturn(authorized());

        java.util.Map<String, Object> noCatalog = new java.util.LinkedHashMap<>(fixture.claims);
        noCatalog.remove("catalogVersion");
        assertThat(body(fixture.service.handle(callRequest("hello"), noCatalog))
                .path("error").path("data").path("kind").asText()).isEqualTo("SCHEMA_DRIFT");

        ObjectNode unknown = callParams();
        unknown.put("name", "mcp.local_readonly.unknown");
        unknown.set("arguments", json.createObjectNode().put("text", "hello"));
        assertThat(body(fixture.service.handle(request("tools/call", unknown), fixture.claims))
                .path("error").path("data").path("kind").asText()).isEqualTo("TOOL_DISABLED");

        ObjectNode extra = callParams();
        extra.set("arguments", json.createObjectNode().put("text", "hello").put("extra", true));
        assertThat(body(fixture.service.handle(request("tools/call", extra), fixture.claims))
                .path("error").path("data").path("kind").asText()).isEqualTo("PARAM_INVALID");
        verifyNoInteractions(fixture.clients);
    }

    @Test
    void unknownMethodAndUnauthorizedRunStayDistinct() {
        Fixture fixture = fixture("mcp:list mcp:call");
        JsonNode unknown = body(fixture.service.handle(
                request("resources/list", json.createObjectNode().put("schemaVersion", 1)),
                fixture.claims));
        assertThat(unknown.path("error").path("code").asInt()).isEqualTo(-32601);
        assertThat(unknown.path("error").path("data").path("kind").asText()).isEqualTo("INTERNAL");

        when(fixture.authorizer.authorize(any()))
                .thenThrow(McpGatewayException.business(McpGatewayException.Kind.UNAUTHORIZED));
        JsonNode unauthorized = body(fixture.service.handle(request("tools/list",
                json.createObjectNode().put("schemaVersion", 1)), fixture.claims));
        assertThat(unauthorized.path("error").path("data").path("kind").asText())
                .isEqualTo("UNAUTHORIZED");
        verifyNoInteractions(fixture.clients);
    }

    @Test
    void revokeDuringCallNeverReturnsSuccessfulBody() {
        Fixture fixture = fixture("mcp:list mcp:call");
        when(fixture.catalogService.build(projectId)).thenReturn(catalog);
        when(fixture.authorizer.authorize(any())).thenReturn(authorized());
        when(fixture.authorizer.stillAuthorized(any())).thenReturn(true, false);
        McpClientFacade client = mock(McpClientFacade.class);
        when(fixture.clients.requireClient(any())).thenReturn(client);
        when(client.callTool(anyString(), any(), any())).thenReturn(
                new McpClientFacade.CallResult(false, null,
                        json.createObjectNode().put("text", "must-not-leak")));

        JsonNode response = body(fixture.service.handle(callRequest("hello"), fixture.claims));

        assertThat(response.path("error").path("data").path("kind").asText()).isEqualTo("TOOL_REVOKED");
        assertThat(response.toString()).doesNotContain("must-not-leak");
    }

    @Test
    void catalogRevocationDuringCallAlsoFencesTheLateResult() {
        Fixture fixture = fixture("mcp:list mcp:call");
        when(fixture.catalogService.build(projectId))
                .thenReturn(catalog, McpGatewayCatalogService.Catalog.of(List.of()));
        when(fixture.authorizer.authorize(any())).thenReturn(authorized());
        when(fixture.authorizer.stillAuthorized(any())).thenReturn(true);
        McpClientFacade client = mock(McpClientFacade.class);
        when(fixture.clients.requireClient(any())).thenReturn(client);
        when(client.callTool(anyString(), any(), any())).thenReturn(
                new McpClientFacade.CallResult(false, null,
                        json.createObjectNode().put("text", "late-secret")));

        JsonNode response = body(fixture.service.handle(callRequest("hello"), fixture.claims));

        assertThat(response.path("error").path("data").path("kind").asText()).isEqualTo("TOOL_REVOKED");
        assertThat(response.toString()).doesNotContain("late-secret");
    }

    @Test
    void auditPersistenceFailureBeforeToolCallFailsClosedWithZeroExternalCalls() {
        AuditService audit = mock(AuditService.class);
        doThrow(new IllegalStateException("audit unavailable"))
                .when(audit).write(any(), any(), anyString(), anyString(), any(), anyMap());
        Fixture fixture = fixture("mcp:list mcp:call", new McpGatewayAuditor(audit));
        when(fixture.catalogService.build(projectId)).thenReturn(catalog);
        when(fixture.authorizer.authorize(any())).thenReturn(authorized());
        when(fixture.authorizer.stillAuthorized(any())).thenReturn(true);

        JsonNode response = body(fixture.service.handle(callRequest("hello"), fixture.claims));

        assertThat(response.path("error").path("data").path("kind").asText()).isEqualTo("INTERNAL");
        assertThat(response.has("result")).isFalse();
        assertThat(response.path("result").has("data")).isFalse();
        verifyNoInteractions(fixture.clients);
    }

    @Test
    void auditPersistenceFailureAfterToolCallReturnsUnknownWithoutSuccessBody() {
        AuditService audit = mock(AuditService.class);
        doNothing().doThrow(new IllegalStateException("audit unavailable"))
                .when(audit).write(any(), any(), anyString(), anyString(), any(), anyMap());
        Fixture fixture = fixture("mcp:list mcp:call", new McpGatewayAuditor(audit));
        when(fixture.catalogService.build(projectId)).thenReturn(catalog);
        when(fixture.authorizer.authorize(any())).thenReturn(authorized());
        when(fixture.authorizer.stillAuthorized(any())).thenReturn(true);
        McpClientFacade client = mock(McpClientFacade.class);
        when(fixture.clients.requireClient(any())).thenReturn(client);
        when(client.callTool(anyString(), any(), any())).thenReturn(
                new McpClientFacade.CallResult(false, null,
                        json.createObjectNode().put("text", "audit-must-not-leak")));

        JsonNode response = body(fixture.service.handle(callRequest("hello"), fixture.claims));

        assertThat(response.path("error").path("data").path("kind").asText())
                .isEqualTo("OUTCOME_UNKNOWN");
        assertThat(response.path("result").has("data")).isFalse();
        assertThat(response.toString()).doesNotContain("audit-must-not-leak");
        verify(client).callTool(anyString(), any(), any());
        verify(audit, times(2)).write(any(), any(), anyString(), anyString(), any(), anyMap());
    }

    @Test
    void auditPersistenceFailureForToolsListReturnsInternalInsteadOfCatalog() {
        AuditService audit = mock(AuditService.class);
        doThrow(new IllegalStateException("audit unavailable"))
                .when(audit).write(any(), any(), anyString(), anyString(), any(), anyMap());
        Fixture fixture = fixture("mcp:list", new McpGatewayAuditor(audit));
        when(fixture.catalogService.build(projectId)).thenReturn(catalog);
        when(fixture.authorizer.authorize(any())).thenReturn(authorized());

        JsonNode response = body(fixture.service.handle(request("tools/list",
                json.createObjectNode().put("schemaVersion", 1)), fixture.claims));

        assertThat(response.path("error").path("data").path("kind").asText()).isEqualTo("INTERNAL");
        assertThat(response.has("result")).isFalse();
        verifyNoInteractions(fixture.clients);
    }

    @Test
    void mcpErrorTimeoutAndRateLimitStayDistinct() {
        Fixture fixture = fixture("mcp:list mcp:call");
        when(fixture.catalogService.build(projectId)).thenReturn(catalog);
        when(fixture.authorizer.authorize(any())).thenReturn(authorized());
        when(fixture.authorizer.stillAuthorized(any())).thenReturn(true);
        McpClientFacade client = mock(McpClientFacade.class);
        when(fixture.clients.requireClient(any())).thenReturn(client);

        when(client.callTool(anyString(), any(), any())).thenReturn(
                new McpClientFacade.CallResult(true, json.createArrayNode()));
        assertThat(body(fixture.service.handle(callRequest("hello"), fixture.claims))
                .path("error").path("data").path("kind").asText()).isEqualTo("OUTCOME_UNKNOWN");

        doThrow(new BusinessException(ErrorCode.AGENT_MCP_TIMEOUT))
                .when(client).callTool(anyString(), any(), any());
        assertThat(body(fixture.service.handle(callRequest("hello"), fixture.claims))
                .path("error").path("data").path("kind").asText()).isEqualTo("OUTCOME_UNKNOWN");

        doThrow(new BusinessException(ErrorCode.AGENT_MCP_RATE_LIMITED))
                .when(client).callTool(anyString(), any(), any());
        assertThat(body(fixture.service.handle(callRequest("hello"), fixture.claims))
                .path("error").path("data").path("kind").asText()).isEqualTo("RATE_LIMITED");

        doThrow(new BusinessException(ErrorCode.AGENT_MCP_ENDPOINT_FORBIDDEN))
                .when(client).callTool(anyString(), any(), any());
        assertThat(body(fixture.service.handle(callRequest("hello"), fixture.claims))
                .path("error").path("data").path("kind").asText()).isEqualTo("NOT_EXECUTED");

        doThrow(new BusinessException(ErrorCode.AGENT_TOOL_RESULT_TOO_LARGE))
                .when(client).callTool(anyString(), any(), any());
        assertThat(body(fixture.service.handle(callRequest("hello"), fixture.claims))
                .path("error").path("data").path("kind").asText()).isEqualTo("TRUNCATED");

        doThrow(new BusinessException(ErrorCode.AGENT_MCP_REDIRECT_BLOCKED))
                .when(client).callTool(anyString(), any(), any());
        assertThat(body(fixture.service.handle(callRequest("hello"), fixture.claims))
                .path("error").path("data").path("kind").asText()).isEqualTo("OUTCOME_UNKNOWN");
    }

    @Test
    void auditNeverContainsFullArgumentsOrResultBodies() {
        Fixture fixture = fixture("mcp:list mcp:call", new McpGatewayAuditor(null));
        when(fixture.catalogService.build(projectId)).thenReturn(catalog);
        when(fixture.authorizer.authorize(any())).thenReturn(authorized());
        when(fixture.authorizer.stillAuthorized(any())).thenReturn(true);
        McpClientFacade client = mock(McpClientFacade.class);
        when(fixture.clients.requireClient(any())).thenReturn(client);
        when(client.callTool(anyString(), any(), any())).thenReturn(
                new McpClientFacade.CallResult(false, null,
                        json.createObjectNode().put("text", "result-secret-body")));
        ObjectNode arguments = json.createObjectNode().put("text", "argument-secret-body");
        ObjectNode params = callParams();
        params.set("arguments", arguments);
        Logger logger = (Logger) LoggerFactory.getLogger(McpGatewayAuditor.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            body(fixture.service.handle(request("tools/call", params), fixture.claims));
        } finally {
            logger.detachAppender(appender);
        }

        String logs = appender.list.stream().map(ILoggingEvent::getFormattedMessage)
                .collect(Collectors.joining("\n"));
        assertThat(logs).contains("method=tools/call").contains("outcome=SUCCEEDED");
        assertThat(logs).doesNotContain("argument-secret-body").doesNotContain("result-secret-body");
    }

    private Fixture fixture(String scopes) {
        return fixture(scopes, mock(McpGatewayAuditor.class));
    }

    private Fixture fixture(String scopes, McpGatewayAuditor auditor) {
        McpGatewayCatalogService catalogService = mock(McpGatewayCatalogService.class);
        McpGatewayAuthorizer authorizer = mock(McpGatewayAuthorizer.class);
        McpConnectionManager clients = mock(McpConnectionManager.class);
        McpGatewayService service = new McpGatewayService(
                new McpGatewayProperties(false, "", "", ""),
                catalogService, authorizer, clients, new McpResultSanitizer(json), auditor, json);
        Map<String, Object> claims = Map.of(
                "projectRef", projectId.toString(),
                "runRef", runId.toString(),
                "catalogVersion", catalog.catalogVersion(),
                "scope", scopes);
        return new Fixture(service, catalogService, authorizer, clients, claims);
    }

    private McpGatewayAuthorizer.AuthorizedContext authorized() {
        return new McpGatewayAuthorizer.AuthorizedContext(projectId, runId, requesterId, "SERVICE");
    }

    private String request(String method, JsonNode params) {
        ObjectNode request = json.createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("id", "call-1");
        request.put("method", method);
        request.set("params", params);
        return request.toString();
    }

    private String callRequest(String text) {
        ObjectNode params = callParams();
        params.set("arguments", json.createObjectNode().put("text", text));
        return request("tools/call", params);
    }

    private ObjectNode callParams() {
        ObjectNode params = json.createObjectNode();
        params.put("schemaVersion", 1);
        params.put("name", "mcp.local_readonly.echo_text");
        params.put("catalogVersion", catalog.catalogVersion());
        params.put("toolSchemaHash", TOOL_HASH);
        params.put("callId", "run-1:tool-1");
        return params;
    }

    private JsonNode body(McpGatewayService.McpGatewayResponse response) {
        assertThat(response.httpStatus()).isEqualTo(200);
        return (JsonNode) response.body();
    }

    private McpGatewayCatalogService.Tool tool() {
        ObjectNode schema = json.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("text").put("type", "string").put("maxLength", 256);
        schema.putArray("required").add("text");
        schema.put("additionalProperties", false);
        return new McpGatewayCatalogService.Tool(
                "mcp.local_readonly.echo_text", "echo_text", connection.id(),
                "local_readonly", connection.version(), "只读回显合成文本", schema,
                TOOL_HASH, connection);
    }

    private McpConnection connection() {
        OffsetDateTime now = OffsetDateTime.now();
        try {
            String oldHash = "a".repeat(64);
            return new McpConnection(UUID.randomUUID(), projectId, "local_readonly", "Local",
                    McpTransport.STREAMABLE_HTTP, "https://example.com/mcp", null,
                    McpAuthType.NONE, null, null, 5_000, 65_536,
                    json.writeValueAsString(List.of("echo_text")), "[]",
                    "[{\"name\":\"echo_text\",\"annotations\":{\"readOnlyHint\":true}}]",
                    "[]", oldHash, oldHash, true, "HEALTHY", null, now,
                    requesterId, 1, now, now);
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }

    private record Fixture(McpGatewayService service, McpGatewayCatalogService catalogService,
            McpGatewayAuthorizer authorizer, McpConnectionManager clients,
            Map<String, Object> claims) {
    }
}
