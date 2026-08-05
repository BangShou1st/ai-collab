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
    private record Managed(int version, String schemaHash, McpClientFacade client) {}
    private final ConcurrentMap<UUID, Managed> clients = new ConcurrentHashMap<>();
    private final McpClientFactory factory;

    public McpConnectionManager(McpClientFactory factory) { this.factory = factory; }

    public McpClientFacade requireClient(McpConnection connection) {
        if (!connection.enabled()) throw new BusinessException(ErrorCode.AGENT_MCP_CONNECTION_DISABLED);
        if (connection.schemaHash() == null || !connection.schemaHash().equals(connection.confirmedSchemaHash()))
            throw new BusinessException(ErrorCode.AGENT_MCP_SCHEMA_CHANGED);
        Managed current = clients.get(connection.id());
        if (current != null && current.version() == connection.version()
                && java.util.Objects.equals(current.schemaHash(), connection.schemaHash())) return current.client();
        invalidate(connection.id());
        McpClientFacade created = factory.create(connection);
        try { created.initialize(); }
        catch (RuntimeException exception) { created.close(); throw exception; }
        Managed raced = clients.putIfAbsent(connection.id(),
                new Managed(connection.version(), connection.schemaHash(), created));
        if (raced != null) { created.close(); return raced.client(); }
        return created;
    }

    public void invalidate(UUID connectionId) {
        Managed removed = clients.remove(connectionId);
        if (removed != null) removed.client().close();
    }

    @Override @PreDestroy
    public void close() {
        clients.values().forEach(value -> value.client().close()); clients.clear();
    }
}
