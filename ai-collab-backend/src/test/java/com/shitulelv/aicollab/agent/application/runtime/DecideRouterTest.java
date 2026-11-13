package com.shitulelv.aicollab.agent.application.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.agent.application.view.AgentRunView;
import com.shitulelv.aicollab.agent.domain.model.AgentRunStatus;
import com.shitulelv.aicollab.agent.domain.model.AgentScenario;
import com.shitulelv.aicollab.agent.domain.model.builtin.ProjectResearchScenario;
import com.shitulelv.aicollab.agent.domain.tool.AgentToolDefinition;
import com.shitulelv.aicollab.agent.infrastructure.decide.DecideDecision;
import com.shitulelv.aicollab.agent.infrastructure.decide.DecideGatewayClient;
import com.shitulelv.aicollab.agent.infrastructure.decide.DecideGatewayErrorCode;
import com.shitulelv.aicollab.agent.infrastructure.decide.DecideGatewayException;
import com.shitulelv.aicollab.agent.infrastructure.decide.DecideGatewayRequest;
import com.shitulelv.aicollab.agent.infrastructure.decide.DecideGatewayResponse;
import com.shitulelv.aicollab.agent.infrastructure.decide.DecideMode;
import com.shitulelv.aicollab.agent.infrastructure.decide.DecideProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DecideRouterTest {

    private static final String READ_TOOL = "search_project_knowledge";
    private static final String WRITE_TOOL = "create_task_after_approval";

    private final ObjectMapper json = new ObjectMapper();
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    @Test
    void javaAndShadowModesDoNotRouteOrCallWorker() {
        DecideGatewayClient gateway = mock(DecideGatewayClient.class);
        for (DecideMode mode : List.of(DecideMode.JAVA, DecideMode.SHADOW)) {
            DecideRouter router = router(mode, gateway);
            DecideRouter.Route route = route(router);
            assertThat(route.handled()).isFalse();
            assertThat(route.marker()).isNull();
        }
        verify(gateway, never()).decide(any());
        assertThat(registry.getMeters()).isEmpty();
    }

    @Test
    void pythonCallToolBecomesGovernedSyntheticTurnAndSendsBoundedRequest() {
        DecideGatewayClient gateway = mock(DecideGatewayClient.class);
        when(gateway.decide(any())).thenReturn(callTool(
                READ_TOOL, json.createObjectNode().put("query", "风险"), 44L));
        DecideRouter router = router(DecideMode.PYTHON, gateway);

        DecideRouter.Route route = route(router);

        assertThat(route.handled()).isTrue();
        assertThat(route.marker()).isEqualTo("python:CALL_TOOL");
        assertThat(route.decision()).isEqualTo("CALL_TOOL");
        assertThat(route.turn().provider()).isEqualTo(DecideRouter.PROVIDER);
        assertThat(route.turn().toolCalls()).singleElement().satisfies(call -> {
            assertThat(call.id()).startsWith("python-decide-");
            assertThat(call.name()).isEqualTo(READ_TOOL);
            assertThat(call.arguments().path("query").asText()).isEqualTo("风险");
        });
        assertThat(route.turn().latencyMs()).isEqualTo(44);
        assertThat(route.turn().usage().inputTokens()).isEqualTo(100);
        assertThat(registry.find(DecideRouter.METRIC_HANDLED).counter().count()).isEqualTo(1);

        ArgumentCaptor<DecideGatewayRequest> request =
                ArgumentCaptor.forClass(DecideGatewayRequest.class);
        verify(gateway).decide(request.capture());
        assertThat(request.getValue().budget().deadlineMs()).isEqualTo(1000);
        assertThat(request.getValue().budget().maxInputTokens()).isEqualTo(24000);
        assertThat(request.getValue().tools())
                .extracting(DecideGatewayRequest.Tool::name)
                .containsExactly(READ_TOOL);
        assertThat(request.getValue().policy().allowWrite()).isFalse();
    }

    @Test
    void unknownOrWriteToolFallsBackWithoutExecutingAnything() {
        DecideGatewayClient gateway = mock(DecideGatewayClient.class);
        when(gateway.decide(any()))
                .thenReturn(callTool("missing_tool", json.createObjectNode(), 1L))
                .thenReturn(callTool(WRITE_TOOL, json.createObjectNode(), 1L));
        DecideRouter router = router(DecideMode.PYTHON, gateway);

        assertThat(route(router).marker()).isEqualTo("python_fallback:unknown_tool");
        assertThat(route(router).marker()).isEqualTo("python_fallback:write_tool");
        assertThat(registry.find(DecideRouter.METRIC_INVALID_TOOL_CALL).counter().count())
                .isEqualTo(2);
    }

    @Test
    void schemaInvalidNonToolDecisionAndWorkerFailureAllFallbackJava() {
        DecideGatewayClient gateway = mock(DecideGatewayClient.class);
        when(gateway.decide(any()))
                .thenReturn(callTool(READ_TOOL, json.createObjectNode().put("wrong", true), 1L))
                .thenReturn(new DecideGatewayResponse(1, DecideDecision.FINAL, null,
                        new DecideGatewayResponse.Usage(null, null, "UNKNOWN"),
                        "v1", "agent-main", 1L))
                .thenThrow(new DecideGatewayException(
                        DecideGatewayErrorCode.TIMEOUT, "decide worker 超过 deadline"))
                .thenThrow(new DecideGatewayException(
                        DecideGatewayErrorCode.SCHEMA, "decide worker invalid json"))
                .thenThrow(new DecideGatewayException(
                        DecideGatewayErrorCode.UNAVAILABLE, "decide worker unavailable"));
        DecideRouter router = router(DecideMode.PYTHON, gateway);

        assertThat(route(router).marker()).isEqualTo("python_fallback:schema");
        assertThat(route(router).marker()).isEqualTo("python_fallback:decision_final");
        assertThat(route(router).marker()).isEqualTo("python_fallback:worker_timeout");
        assertThat(route(router).marker()).isEqualTo("python_fallback:worker_schema");
        assertThat(route(router).marker()).isEqualTo("python_fallback:worker_unavailable");
        assertThat(registry.find(DecideRouter.METRIC_FALLBACK).tags("reason", "schema").counter().count())
                .isEqualTo(1);
        assertThat(registry.find(DecideRouter.METRIC_FALLBACK)
                .tags("reason", "worker_timeout").counter().count()).isEqualTo(1);
    }

    @Test
    void ineligibleRunsAndEmptyBudgetsFallbackWithoutCallingWorker() {
        DecideGatewayClient gateway = mock(DecideGatewayClient.class);
        DecideRouter router = router(DecideMode.PYTHON, gateway);

        assertThat(router.route(run(1), new ProjectResearchScenario(), exposed(), List.of(),
                1000, 1000, false, "model-call").marker())
                .isEqualTo("python_fallback:scenario");
        assertThat(router.route(run(0), new ProjectResearchScenario(), exposed(), List.of(),
                0, 1000, false, "model-call").marker())
                .isEqualTo("python_fallback:budget");
        assertThat(router.route(run(0), new ProjectResearchScenario(), exposed(), List.of(),
                1000, 0, false, "model-call").marker())
                .isEqualTo("python_fallback:deadline");
        assertThat(router.route(run(0), new ProjectResearchScenario(), exposed(), List.of(),
                1000, 1000, true, "model-call").marker())
                .isEqualTo("python_fallback:scenario");
        assertThat(router.route(run(0), new ProjectResearchScenario(), exposed(), List.of(),
                1000, 1000, false, null).marker())
                .isEqualTo("python_fallback:model_call_id");

        verify(gateway, never()).decide(any());
    }

    private DecideRouter router(DecideMode mode, DecideGatewayClient gateway) {
        DecideProperties properties = new DecideProperties(
                mode, "http://worker.test", 1000, 4, 16, 32000);
        AgentDecideShadowService snapshots = new AgentDecideShadowService(
                properties, gateway, json, registry);
        return new DecideRouter(properties, gateway, snapshots, registry);
    }

    private DecideRouter.Route route(DecideRouter router) {
        return router.route(run(0), new ProjectResearchScenario(), exposed(), List.of(),
                30000, 5000, false, "model-call");
    }

    private static DecideGatewayResponse callTool(String name, com.fasterxml.jackson.databind.JsonNode args,
            long latency) {
        return new DecideGatewayResponse(1, DecideDecision.CALL_TOOL,
                new DecideGatewayResponse.ToolCall(name, args),
                new DecideGatewayResponse.Usage(100, 10, "PROVIDER"),
                "2026-11-11.1", "agent-main", latency);
    }

    private static List<AgentToolDefinition> exposed() {
        return List.of(
                AgentToolDefinition.fromJson(READ_TOOL, "检索项目知识",
                        "{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\"}},"
                                + "\"required\":[\"query\"],\"additionalProperties\":false}", false),
                AgentToolDefinition.openObject(WRITE_TOOL, "创建任务", true));
    }

    private static AgentRunView run(int depth) {
        OffsetDateTime now = OffsetDateTime.now();
        return new AgentRunView(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                depth > 0 ? UUID.randomUUID() : null, "OWNER", depth, "梳理风险",
                AgentRunStatus.RUNNING, 20, 40, 4, null, null, 3, 1, 0, 100, 50,
                100L, 50L, false, false, false, 0, null, null, null,
                "PROJECT_RESEARCH", 1, 2, false, now, now);
    }
}
