package com.shitulelv.aicollab.agent.infrastructure.mcp.gateway;

import com.shitulelv.aicollab.internal.ai.token.InternalTokenIssuer;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 仅从已验签 claims（或显式 local/test 静态绑定）解析 Gateway 身份。
 * 请求体中的 projectId/userId/role/projectRef/runRef 一律不参与身份解析。
 */
final class McpGatewayIdentity {

    private static final Pattern SHA256_VERSION = Pattern.compile("sha256:[0-9a-f]{64}");

    private final UUID projectRef;
    private final UUID runRef;
    private final String catalogVersion;
    private final boolean staticTest;
    private final Set<String> scopes;

    private McpGatewayIdentity(UUID projectRef, UUID runRef, String catalogVersion,
            boolean staticTest, Set<String> scopes) {
        this.projectRef = projectRef;
        this.runRef = runRef;
        this.catalogVersion = catalogVersion;
        this.staticTest = staticTest;
        this.scopes = Set.copyOf(scopes);
    }

    static McpGatewayIdentity resolve(Map<String, Object> claims, McpGatewayProperties properties) {
        if (claims == null || claims.isEmpty()) {
            return staticIdentity(properties);
        }
        UUID projectRef = uuidClaim(claims.get("projectRef"));
        UUID runRef = uuidClaim(claims.get("runRef"));
        if (projectRef == null || runRef == null) {
            throw new McpGatewayHttpAuthException();
        }
        String catalogVersion = textClaim(claims.get("catalogVersion"));
        if (catalogVersion != null && !SHA256_VERSION.matcher(catalogVersion).matches()) {
            throw new McpGatewayHttpAuthException();
        }
        return new McpGatewayIdentity(projectRef, runRef, catalogVersion, false, scopes(claims.get("scope")));
    }

    boolean canList() {
        return staticTest || scopes.contains(InternalTokenIssuer.SCOPE_MCP_LIST)
                || scopes.contains(InternalTokenIssuer.SCOPE_MCP_CALL);
    }

    boolean canCall() {
        return staticTest || scopes.contains(InternalTokenIssuer.SCOPE_MCP_CALL);
    }

    UUID projectRef() {
        return projectRef;
    }

    UUID runRef() {
        return runRef;
    }

    String catalogVersion() {
        return catalogVersion;
    }

    boolean staticTest() {
        return staticTest;
    }

    private static McpGatewayIdentity staticIdentity(McpGatewayProperties properties) {
        if (!properties.completeStaticTestBinding()) {
            throw new McpGatewayHttpAuthException();
        }
        UUID projectRef = uuidClaim(properties.staticTestProjectRef());
        UUID runRef = uuidClaim(properties.staticTestRunRef());
        String catalogVersion = properties.staticTestCatalogVersion();
        if (projectRef == null || runRef == null || !SHA256_VERSION.matcher(catalogVersion).matches()) {
            throw new McpGatewayHttpAuthException();
        }
        return new McpGatewayIdentity(projectRef, runRef, catalogVersion, true,
                Set.of(InternalTokenIssuer.SCOPE_MCP_LIST, InternalTokenIssuer.SCOPE_MCP_CALL));
    }

    private static Set<String> scopes(Object value) {
        Set<String> result = new LinkedHashSet<>();
        if (value instanceof Collection<?> collection) {
            for (Object item : collection) {
                if (item != null) {
                    result.add(item.toString());
                }
            }
        } else if (value != null) {
            for (String item : value.toString().split("\\s+")) {
                if (!item.isBlank()) {
                    result.add(item);
                }
            }
        }
        return result;
    }

    private static UUID uuidClaim(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return UUID.fromString(value.toString());
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    private static String textClaim(Object value) {
        if (value == null) {
            return null;
        }
        String text = value.toString().strip();
        return text.isEmpty() ? null : text;
    }

    static final class McpGatewayHttpAuthException extends RuntimeException {
    }
}
