package com.shitulelv.aicollab.agent.infrastructure.mcp.gateway;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Gateway 低基数指标。标签只允许 method/outcome/reason/connectionCode，
 * 不放工具参数、结果、用户输入或错误正文。
 */
final class McpGatewayMetrics {

    static final String REQUESTS = "mcp.gateway.requests";
    static final String LATENCY = "mcp.gateway.latency";
    static final String TOOL_CALLS = "mcp.gateway.tool_calls";
    static final String DENIED = "mcp.gateway.denied";
    static final String SCHEMA_DRIFT = "mcp.gateway.schema_drift";
    static final String AUDIT_FAILURES = "mcp.gateway.audit_failures";
    static final String EGRESS_BLOCKED = "mcp.gateway.egress_blocked";
    static final String RESULT_BYTES = "mcp.gateway.result_bytes";
    static final String INFLIGHT = "mcp.gateway.inflight";

    private final MeterRegistry registry;
    private final AtomicInteger inflight = new AtomicInteger();

    McpGatewayMetrics(MeterRegistry registry) {
        this.registry = registry;
        Gauge.builder(INFLIGHT, inflight, AtomicInteger::doubleValue)
                .description("MCP Gateway requests currently being handled")
                .register(registry);
    }

    void requestStarted() {
        try {
            inflight.incrementAndGet();
        } catch (RuntimeException ignored) {
            // Metrics must never change the security decision of a gateway request.
        }
    }

    void requestFinished() {
        try {
            inflight.updateAndGet(value -> Math.max(0, value - 1));
        } catch (RuntimeException ignored) {
            // Metrics are best-effort.
        }
    }

    void record(McpGatewayAuditor.Event event) {
        try {
            recordSafely(event);
        } catch (RuntimeException ignored) {
            // Metrics are best-effort; audit failure handling remains authoritative.
        }
    }

    private void recordSafely(McpGatewayAuditor.Event event) {
        String method = safe(event.method());
        String outcome = safe(event.outcome());
        String reason = safe(event.reason());
        Counter.builder(REQUESTS)
                .tag("method", method)
                .tag("outcome", outcome)
                .register(registry)
                .increment();
        Timer.builder(LATENCY)
                .tag("method", method)
                .tag("outcome", outcome)
                .publishPercentileHistogram()
                .register(registry)
                .record(Duration.ofMillis(Math.max(0, event.latencyMs())));
        DistributionSummary.builder(RESULT_BYTES)
                .tag("outcome", outcome)
                .publishPercentileHistogram()
                .register(registry)
                .record(Math.max(0, event.responseBytes()));
        if ("tools/call".equals(method)) {
            Counter.builder(TOOL_CALLS)
                    .tag("connectionCode", safe(event.connectionCode()))
                    .tag("outcome", outcome)
                    .register(registry)
                    .increment();
        }
        if ("DENY".equals(event.decision())) {
            Counter.builder(DENIED)
                    .tag("reason", reason)
                    .register(registry)
                    .increment();
        }
        if ("SCHEMA_DRIFT".equals(reason)) {
            Counter.builder(SCHEMA_DRIFT)
                    .tag("connectionCode", safe(event.connectionCode()))
                    .register(registry)
                    .increment();
        }
        if ("AUDIT_UNAVAILABLE".equals(reason)) {
            Counter.builder(AUDIT_FAILURES)
                    .tag("outcome", outcome)
                    .register(registry)
                    .increment();
        }
    }

    private static String safe(String value) {
        return value == null || value.isBlank() ? "none" : value;
    }
}
