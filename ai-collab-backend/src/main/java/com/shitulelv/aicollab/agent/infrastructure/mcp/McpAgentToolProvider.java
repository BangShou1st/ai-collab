package com.shitulelv.aicollab.agent.infrastructure.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.agent.domain.model.AgentExecutionContext;
import com.shitulelv.aicollab.agent.domain.tool.AgentTool;
import com.shitulelv.aicollab.agent.domain.tool.AgentToolContext;
import com.shitulelv.aicollab.agent.domain.tool.AgentToolDefinition;
import com.shitulelv.aicollab.agent.domain.tool.AgentToolResult;
import com.shitulelv.aicollab.agent.infrastructure.mcp.api.McpBindingView;
import com.shitulelv.aicollab.agent.infrastructure.tool.AgentToolProvider;
import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public class McpAgentToolProvider implements AgentToolProvider {
    private static final Pattern SAFE_NAME = Pattern.compile("[^a-zA-Z0-9_]");
    private final McpRepository repository;
    private final McpConnectionManager clients;
    private final McpResultSanitizer sanitizer;
    private final ObjectMapper json;

    public McpAgentToolProvider(McpRepository repository, McpConnectionManager clients,
                                McpResultSanitizer sanitizer, ObjectMapper json) {
        this.repository = repository; this.clients = clients; this.sanitizer = sanitizer; this.json = json;
    }

    @Override
    public List<AgentTool> tools(AgentExecutionContext context) {
        List<AgentTool> result = new ArrayList<>();
        for (McpBindingView binding : repository.bindings(context.projectId())) {
            if (!binding.enabled() || !binding.connectionEnabled()) continue;
            McpConnection connection = repository.find(binding.connectionId()).orElse(null);
            if (connection == null || connection.schemaHash() == null
                    || !connection.schemaHash().equals(connection.confirmedSchemaHash())) continue;
            Set<String> projectAllowed = Set.copyOf(binding.allowedTools());
            Set<String> systemAllowed = strings(connection.toolAllowlistJson());
            try {
                for (JsonNode discovered : json.readTree(connection.discoveredToolsJson())) {
                    String serverName = discovered.path("name").asText();
                    if (projectAllowed.contains(serverName) && systemAllowed.contains(serverName)
                            && isReadOnly(discovered))
                        result.add(new McpAgentTool(context.projectId(), connection.id(), connection.code(),
                                connection.schemaHash(), serverName,
                                discovered.path("description").asText("外部 MCP 只读工具"),
                                discovered.path("inputSchema"), repository, clients, sanitizer, json));
                }
            } catch (Exception exception) {
                throw new IllegalStateException("MCP 工具发现快照无效", exception);
            }
        }
        return List.copyOf(result);
    }

    private Set<String> strings(String source) {
        try { Set<String> values = new HashSet<>(); for (JsonNode node : json.readTree(source)) values.add(node.asText()); return values; }
        catch (Exception exception) { return Set.of(); }
    }

    private static boolean isReadOnly(JsonNode discovered) {
        return discovered.path("annotations").path("readOnlyHint").asBoolean(false);
    }

    private static final class McpAgentTool implements AgentTool {
        private final java.util.UUID projectId;
        private final java.util.UUID connectionId;
        private final String schemaHash;
        private final String name;
        private final String serverName;
        private final AgentToolDefinition definition;
        private final McpRepository repository;
        private final McpConnectionManager clients;
        private final McpResultSanitizer sanitizer;
        private final ObjectMapper json;

        private McpAgentTool(java.util.UUID projectId, java.util.UUID connectionId, String code,
                String schemaHash, String serverName,
                String description, JsonNode schema, McpRepository repository,
                McpConnectionManager clients, McpResultSanitizer sanitizer, ObjectMapper json) {
            this.projectId = projectId; this.connectionId = connectionId;
            this.schemaHash = schemaHash; this.serverName = serverName;
            this.name = "mcp." + code + "." + SAFE_NAME.matcher(serverName).replaceAll("_").toLowerCase(Locale.ROOT);
            JsonNode safeSchema = schema != null && schema.isObject() ? schema
                    : com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode().put("type", "object");
            this.definition = new AgentToolDefinition(name, description.isBlank() ? "外部 MCP 只读工具" : description,
                    safeSchema, false);
            this.repository = repository; this.clients = clients; this.sanitizer = sanitizer; this.json = json;
        }

        @Override public String name() { return name; }
        @Override public boolean writesBusinessData() { return false; }
        @Override public AgentToolDefinition definition() { return definition; }
        @Override public AgentToolResult execute(AgentToolContext context, JsonNode arguments) {
            McpBindingView binding = repository.binding(projectId, connectionId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.AGENT_MCP_CONNECTION_DISABLED));
            if (!binding.enabled() || !binding.connectionEnabled()
                    || !binding.allowedTools().contains(serverName)) {
                throw new BusinessException(ErrorCode.AGENT_MCP_CONNECTION_DISABLED);
            }
            McpConnection current = repository.find(connectionId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.AGENT_MCP_CONNECTION_NOT_FOUND));
            if (!current.enabled()) {
                throw new BusinessException(ErrorCode.AGENT_MCP_CONNECTION_DISABLED);
            }
            if (current.schemaHash() == null
                    || !current.schemaHash().equals(current.confirmedSchemaHash())
                    || !current.schemaHash().equals(schemaHash)) {
                throw new BusinessException(ErrorCode.AGENT_MCP_SCHEMA_CHANGED);
            }
            if (!allowedAndStillReadOnly(current)) {
                throw new BusinessException(ErrorCode.AGENT_MCP_CONNECTION_DISABLED);
            }
            McpClientFacade.CallResult raw = clients.requireClient(current).callTool(
                    serverName, arguments, Duration.ofMillis(current.timeoutMs()));
            return new AgentToolResult(sanitizer.sanitize(raw.content(), current.maxResultBytes()), List.of(), List.of());
        }

        private boolean allowedAndStillReadOnly(McpConnection current) {
            try {
                Set<String> allowlist = new HashSet<>();
                for (JsonNode value : json.readTree(current.toolAllowlistJson())) {
                    allowlist.add(value.asText());
                }
                if (!allowlist.contains(serverName)) return false;
                for (JsonNode tool : json.readTree(current.discoveredToolsJson())) {
                    if (serverName.equals(tool.path("name").asText())) return isReadOnly(tool);
                }
                return false;
            } catch (Exception exception) {
                return false;
            }
        }
    }
}
