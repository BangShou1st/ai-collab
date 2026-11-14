package com.shitulelv.aicollab.agent.infrastructure.mcp.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.internal.ai.InternalAiTokenFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 仅在 {@code agent.mcp.gateway.enabled=true} 时成为 Spring Bean。
 */
@RestController
@ConditionalOnProperty(name = "agent.mcp.gateway.enabled", havingValue = "true")
@RequestMapping("/internal/ai/v1")
public class McpGatewayController {

    private final McpGatewayService service;
    private final ObjectMapper json;

    McpGatewayController(McpGatewayService service, ObjectMapper json) {
        this.service = service;
        this.json = json;
    }

    @PostMapping(value = "/mcp", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<byte[]> mcp(HttpServletRequest request) throws IOException {
        byte[] body = request.getInputStream().readNBytes(McpGatewayService.MAX_REQUEST_BYTES + 1);
        String payload = new String(body, StandardCharsets.UTF_8);
        McpGatewayService.McpGatewayResponse response = service.handle(payload, claims(request));
        return ResponseEntity.status(response.httpStatus())
                .contentType(MediaType.APPLICATION_JSON)
                .body(json.writeValueAsBytes(response.body()));
    }

    private static Map<String, Object> claims(HttpServletRequest request) {
        Object value = request.getAttribute(InternalAiTokenFilter.VERIFIED_CLAIMS_ATTRIBUTE);
        if (!(value instanceof Map<?, ?> raw)) {
            return Map.of();
        }
        Map<String, Object> claims = new LinkedHashMap<>();
        raw.forEach((key, item) -> {
            if (key != null) {
                claims.put(key.toString(), item);
            }
        });
        return Map.copyOf(claims);
    }
}
