package com.shitulelv.aicollab.agent.infrastructure.mcp;

import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
public class McpConnectionManager implements AutoCloseable {
    private record CacheKey(UUID projectId, UUID connectionId) {}
    private record Managed(int version, String schemaHash, McpClientFacade client) {}
    private final ConcurrentMap<CacheKey, Managed> clients = new ConcurrentHashMap<>();
    private final McpClientFactory factory;

    public McpConnectionManager(McpClientFactory factory) { this.factory = factory; }

    public McpClientFacade requireClient(McpConnection connection) {
        if (!connection.enabled()) throw new BusinessException(ErrorCode.AGENT_MCP_CONNECTION_DISABLED);
        if (connection.schemaHash() == null || !connection.schemaHash().equals(connection.confirmedSchemaHash()))
            throw new BusinessException(ErrorCode.AGENT_MCP_SCHEMA_CHANGED);
        CacheKey key = new CacheKey(connection.projectId(), connection.id());
        Managed current = clients.get(key);
        if (current != null && current.version() == connection.version()
                && java.util.Objects.equals(current.schemaHash(), connection.schemaHash())) return current.client();
        invalidate(connection.projectId(), connection.id());
        McpClientFacade created = factory.create(connection);
        try { created.initialize(); }
        catch (RuntimeException exception) { created.close(); throw exception; }
        Managed raced = clients.putIfAbsent(key,
                new Managed(connection.version(), connection.schemaHash(), created));
        if (raced != null) { created.close(); return raced.client(); }
        return created;
    }

    public void invalidate(UUID projectId, UUID connectionId) {
        Managed removed = clients.remove(new CacheKey(projectId, connectionId));
        if (removed != null) removed.client().close();
    }

    @Override @PreDestroy
    public void close() {
        clients.values().forEach(value -> value.client().close()); clients.clear();
    }
}
