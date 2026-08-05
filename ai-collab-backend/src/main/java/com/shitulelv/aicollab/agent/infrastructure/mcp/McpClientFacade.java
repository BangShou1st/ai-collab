package com.shitulelv.aicollab.agent.infrastructure.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.List;

public interface McpClientFacade extends AutoCloseable {
    record ServerInfo(String name, String version) {}
    record DiscoveredTool(String name, String description, JsonNode inputSchema,
                          JsonNode outputSchema, JsonNode annotations) {}
    record DiscoveredResource(String uri, String name, String description, String mimeType) {}
    record CallResult(boolean error, JsonNode content) {}
    record ReadResourceResult(boolean error, JsonNode content) {}

    ServerInfo initialize();
    List<DiscoveredTool> listTools();
    List<DiscoveredResource> listResources();
    CallResult callTool(String serverToolName, JsonNode arguments, Duration timeout);
    ReadResourceResult readResource(String uri, Duration timeout);
    @Override void close();
}
