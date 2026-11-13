package com.shitulelv.aicollab.agent.infrastructure.mcp.gateway;

import java.util.UUID;

interface McpGatewayAuthorizer {

    record AuthorizedContext(UUID projectRef, UUID runRef, UUID requesterId, String actorType) {
    }

    AuthorizedContext authorize(McpGatewayIdentity identity);

    boolean stillAuthorized(McpGatewayIdentity identity);
}
