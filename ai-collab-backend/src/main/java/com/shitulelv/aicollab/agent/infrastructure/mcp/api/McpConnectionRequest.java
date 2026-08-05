package com.shitulelv.aicollab.agent.infrastructure.mcp.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.shitulelv.aicollab.agent.infrastructure.mcp.McpAuthType;
import com.shitulelv.aicollab.agent.infrastructure.mcp.McpTransport;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

public record McpConnectionRequest(
        @NotBlank @Pattern(regexp="[a-z][a-z0-9-]{2,59}") String code,
        @NotBlank @Size(max=120) String name,
        @NotNull McpTransport transport,
        @Size(max=1000) String endpoint,
        JsonNode stdioCommand,
        @NotNull McpAuthType authType,
        @Size(max=8192) String credential,
        @Min(1000) @Max(60000) int timeoutMs,
        @Min(1024) @Max(262144) int maxResultBytes,
        List<String> toolAllowlist,
        List<String> resourceAllowlist,
        int version) {
}
