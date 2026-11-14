package com.shitulelv.aicollab.agent.infrastructure.mcp.gateway;

import com.shitulelv.aicollab.project.application.service.AuditService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * MCP Gateway 统一审计。只记录低敏元数据，不记录参数、结果、凭据或 Prompt。
 */
final class McpGatewayAuditor {

    private static final Logger log = LoggerFactory.getLogger(McpGatewayAuditor.class);

    record Event(
            Instant timestamp,
            String method,
            String actorType,
            UUID actorRef,
            UUID projectRef,
            UUID runRef,
            UUID requesterId,
            UUID connectionId,
            String connectionCode,
            String toolName,
            String catalogVersion,
            String toolSchemaHash,
            String decision,
            String reason,
            long latencyMs,
            int requestBytes,
            int responseBytes,
            String outcome) {
    }

    private final AuditService audit;

    McpGatewayAuditor(AuditService audit) {
        this.audit = audit;
    }

    /**
     * 写入结构化日志并持久化审计。数据库写入失败必须向上抛出，由调用方 fail closed；
     * 结构化日志不能替代持久化审计成功。
     */
    void record(Event event) {
        logOnly(event);
        if (audit == null || event.projectRef() == null || event.requesterId() == null) {
            return;
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("method", safe(event.method()));
        detail.put("actorType", safe(event.actorType()));
        detail.put("actorRef", safe(event.actorRef()));
        detail.put("runRef", safe(event.runRef()));
        detail.put("connectionCode", safe(event.connectionCode()));
        detail.put("toolName", safe(event.toolName()));
        detail.put("catalogVersion", safe(event.catalogVersion()));
        detail.put("toolSchemaHash", safe(event.toolSchemaHash()));
        detail.put("decision", safe(event.decision()));
        detail.put("reason", safe(event.reason()));
        detail.put("latencyMs", event.latencyMs());
        detail.put("requestBytes", event.requestBytes());
        detail.put("responseBytes", event.responseBytes());
        detail.put("outcome", safe(event.outcome()));
        audit.write(event.projectRef(), event.requesterId(), action(event),
                "AGENT_MCP_GATEWAY", event.connectionId(), detail);
    }

    /** 审计失败后仍保留结构化日志，但不能视为审计已成功。 */
    void logOnly(Event event) {
        log.info("mcp_gateway_audit timestamp={} method={} actorType={} actorRef={} projectRef={} runRef={}"
                        + " connectionCode={} toolName={} catalogVersion={} toolSchemaHash={}"
                        + " decision={} reason={} latencyMs={} requestBytes={} responseBytes={} outcome={}",
                event.timestamp(), safe(event.method()), safe(event.actorType()), safe(event.actorRef()),
                safe(event.projectRef()), safe(event.runRef()), safe(event.connectionCode()),
                safe(event.toolName()), safe(event.catalogVersion()), safe(event.toolSchemaHash()),
                safe(event.decision()), safe(event.reason()), event.latencyMs(),
                event.requestBytes(), event.responseBytes(), safe(event.outcome()));
    }

    private static String action(Event event) {
        return "tools/call".equals(safe(event.method()))
                ? "AGENT_MCP_GATEWAY_TOOLS_CALL"
                : "AGENT_MCP_GATEWAY_TOOLS_LIST";
    }

    private static String safe(Object value) {
        if (value == null) {
            return "";
        }
        String raw = value.toString();
        StringBuilder safe = new StringBuilder(Math.min(raw.length(), 200));
        for (int index = 0; index < raw.length() && safe.length() < 200; ) {
            int codePoint = raw.codePointAt(index);
            if (!Character.isISOControl(codePoint) && Character.getType(codePoint) != Character.FORMAT) {
                safe.appendCodePoint(codePoint);
            }
            index += Character.charCount(codePoint);
        }
        return safe.toString();
    }
}
