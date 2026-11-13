package com.shitulelv.aicollab.agent.infrastructure.mcp.gateway;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class McpGatewayMetricsTest {

    @Test
    void recordsLowCardinalityRequestToolDeniedAndDriftMetrics() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        McpGatewayMetrics metrics = new McpGatewayMetrics(registry);
        UUID project = UUID.randomUUID();
        UUID run = UUID.randomUUID();
        UUID connection = UUID.randomUUID();

        metrics.requestStarted();
        assertThat(registry.get(McpGatewayMetrics.INFLIGHT).gauge().value()).isEqualTo(1.0);
        metrics.requestFinished();
        assertThat(registry.get(McpGatewayMetrics.INFLIGHT).gauge().value()).isZero();

        metrics.record(event(project, run, connection, "local_fixture", "ALLOW",
                "", "SUCCEEDED", 120, 42));
        metrics.record(event(project, run, connection, "local_fixture", "DENY",
                "SCHEMA_DRIFT", "SCHEMA_DRIFT", 3, 0));

        assertThat(registry.get(McpGatewayMetrics.REQUESTS)
                .tag("method", "tools/call").tag("outcome", "SUCCEEDED")
                .counter().count()).isEqualTo(1.0);
        assertThat(registry.get(McpGatewayMetrics.TOOL_CALLS)
                .tag("connectionCode", "local_fixture").tag("outcome", "SUCCEEDED")
                .counter().count()).isEqualTo(1.0);
        assertThat(registry.get(McpGatewayMetrics.DENIED)
                .tag("reason", "SCHEMA_DRIFT").counter().count()).isEqualTo(1.0);
        assertThat(registry.get(McpGatewayMetrics.SCHEMA_DRIFT)
                .tag("connectionCode", "local_fixture").counter().count()).isEqualTo(1.0);
        assertThat(registry.get(McpGatewayMetrics.LATENCY)
                .tag("method", "tools/call").tag("outcome", "SUCCEEDED")
                .timer().count()).isEqualTo(1);
        assertThat(registry.get(McpGatewayMetrics.RESULT_BYTES)
                .tag("outcome", "SUCCEEDED").summary().max()).isEqualTo(120.0);
    }

    private McpGatewayAuditor.Event event(UUID project, UUID run, UUID connection,
            String connectionCode, String decision, String reason, String outcome,
            int responseBytes, int requestBytes) {
        return new McpGatewayAuditor.Event(
                Instant.now(), "tools/call", "SERVICE", run, project, run, run,
                connection, connectionCode, "mcp.local_fixture.echo_text",
                "sha256:" + "a".repeat(64), "sha256:" + "b".repeat(64),
                decision, reason, 12, requestBytes, responseBytes, outcome);
    }
}
