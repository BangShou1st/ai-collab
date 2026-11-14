package com.shitulelv.aicollab.agent.infrastructure.mcp.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.shitulelv.aicollab.agent.infrastructure.mcp.McpConnection;
import com.shitulelv.aicollab.agent.infrastructure.mcp.McpProviderAdmission;
import com.shitulelv.aicollab.agent.infrastructure.mcp.McpRepository;
import com.shitulelv.aicollab.agent.infrastructure.mcp.McpTransport;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 项目级只读 MCP 目录。目录只包含同时满足连接确认、白名单、只读声明和清洗条件的工具。
 */
final class McpGatewayCatalogService {

    private static final Pattern EXPOSED_NAME = Pattern.compile("mcp\\.[a-z0-9_]+\\.[a-z0-9_]+");

    record Tool(
            String name,
            String serverName,
            UUID connectionId,
            String connectionCode,
            int bindingVersion,
            String description,
            ObjectNode inputSchema,
            String toolSchemaHash,
            McpConnection connection) {
    }

    record Catalog(String catalogVersion, List<Tool> tools) {

        static Catalog of(List<Tool> tools) {
            List<Tool> sorted = tools.stream().sorted(Comparator.comparing(Tool::name)).toList();
            return new Catalog(catalogVersion(sorted), sorted);
        }

        Optional<Tool> tool(String name) {
            return tools.stream().filter(tool -> tool.name().equals(name)).findFirst();
        }

        private static String catalogVersion(List<Tool> tools) {
            ObjectMapper mapper = new ObjectMapper();
            ObjectNode root = mapper.createObjectNode();
            root.put("schemaVersion", 1);
            ArrayNode entries = root.putArray("tools");
            for (Tool tool : tools) {
                ObjectNode entry = entries.addObject();
                entry.put("name", tool.name());
                entry.put("toolSchemaHash", tool.toolSchemaHash());
            }
            return McpCanonicalJson.sha256(root);
        }
    }

    private final McpRepository repository;
    private final ObjectMapper json;
    private final McpSchemaSanitizer sanitizer;

    McpGatewayCatalogService(McpRepository repository, ObjectMapper json, McpSchemaSanitizer sanitizer) {
        this.repository = repository;
        this.json = json;
        this.sanitizer = sanitizer;
    }

    Catalog build(UUID projectId) {
        List<Tool> tools = new ArrayList<>();
        Set<String> exposedNames = new HashSet<>();
        Map<String, List<McpConnection>> connectionsByCode = new LinkedHashMap<>();
        for (McpConnection connection : repository.listByProject(projectId)) {
            if (!projectId.equals(connection.projectId()) || !connectionEligible(connection)) {
                continue;
            }
            Optional<String> connectionCode = sanitizer.component(connection.code());
            if (connectionCode.isEmpty()) {
                continue;
            }
            connectionsByCode.computeIfAbsent(connectionCode.get(), ignored -> new ArrayList<>())
                    .add(connection);
        }
        for (Map.Entry<String, List<McpConnection>> entry : connectionsByCode.entrySet()) {
            if (entry.getValue().size() != 1) {
                continue;
            }
            List<Tool> connectionTools = connectionTools(entry.getValue().getFirst(), entry.getKey());
            boolean collision = connectionTools.stream().anyMatch(tool -> !exposedNames.add(tool.name()));
            if (!collision) {
                tools.addAll(connectionTools);
            }
        }
        return Catalog.of(tools);
    }

    private List<Tool> connectionTools(McpConnection connection, String connectionCode) {
        Set<String> allowed = strings(connection.toolAllowlistJson());
        List<Tool> tools = new ArrayList<>();
        Set<String> serverNames = new HashSet<>();
        Set<String> exposed = new HashSet<>();
        JsonNode discovered;
        try {
            discovered = json.readTree(connection.discoveredToolsJson());
        } catch (Exception invalid) {
            return List.of();
        }
        if (discovered == null || !discovered.isArray()) {
            return List.of();
        }
        for (JsonNode tool : discovered) {
            JsonNode nameNode = tool.get("name");
            if (nameNode == null || !nameNode.isTextual() || nameNode.textValue().isBlank()) {
                return List.of();
            }
            String rawName = nameNode.textValue();
            if (!allowed.contains(rawName) || !readOnly(tool)) {
                continue;
            }
            Optional<String> normalizedName = sanitizer.component(rawName);
            if (normalizedName.isEmpty() || !serverNames.add(normalizedName.get())) {
                return List.of();
            }
            String exposedName = "mcp." + connectionCode + "." + normalizedName.get();
            if (exposedName.length() > 160 || !EXPOSED_NAME.matcher(exposedName).matches()
                    || !exposed.add(exposedName)) {
                return List.of();
            }
            Optional<String> description = sanitizer.description(tool.path("description").asText(""));
            if (description.isEmpty()) {
                continue;
            }
            ObjectNode schema;
            try {
                schema = sanitizer.schema(tool.get("inputSchema"));
            } catch (McpSchemaSanitizer.SchemaRejectedException rejected) {
                continue;
            }
            ObjectNode hashInput = json.createObjectNode();
            ObjectNode annotations = hashInput.putObject("annotations");
            annotations.put("readOnlyHint", true);
            hashInput.put("description", description.get());
            hashInput.set("inputSchema", schema);
            hashInput.put("name", exposedName);
            String hash;
            try {
                hash = McpCanonicalJson.sha256(hashInput);
            } catch (IllegalArgumentException invalid) {
                continue;
            }
            tools.add(new Tool(exposedName, rawName, connection.id(), connectionCode,
                    connection.version(), description.get(), schema, hash, connection));
        }
        return List.copyOf(tools);
    }

    private boolean connectionEligible(McpConnection connection) {
        return connection.enabled()
                && connection.transport() != McpTransport.STDIO
                && connection.schemaHash() != null
                && connection.schemaHash().equals(connection.confirmedSchemaHash())
                && repository.findAdmission(connection.id())
                .filter(McpProviderAdmission::active)
                .isPresent();
    }

    private static boolean readOnly(JsonNode tool) {
        return tool.path("annotations").path("readOnlyHint").asBoolean(false);
    }

    private Set<String> strings(String raw) {
        try {
            JsonNode root = json.readTree(raw);
            if (root == null || !root.isArray()) {
                return Set.of();
            }
            Set<String> values = new HashSet<>();
            for (JsonNode value : root) {
                if (value.isTextual()) {
                    values.add(value.textValue());
                }
            }
            return values;
        } catch (Exception invalid) {
            return Set.of();
        }
    }
}
