package com.shitulelv.aicollab.agent.infrastructure.mcp.api;

import com.shitulelv.aicollab.agent.infrastructure.mcp.McpAdministrationService;
import com.shitulelv.aicollab.common.api.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/agent/mcp-bindings")
public class ProjectMcpController {
    private final McpAdministrationService service;
    public ProjectMcpController(McpAdministrationService service) { this.service = service; }

    @GetMapping public ApiResponse<List<McpBindingView>> list(
            @PathVariable UUID projectId, @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.success(service.bindings(projectId, AdminMcpController.userId(jwt)));
    }

    @PutMapping("/{connectionId}") public ApiResponse<McpBindingView> bind(
            @PathVariable UUID projectId, @PathVariable UUID connectionId,
            @Valid @RequestBody McpBindingRequest request, @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.success(service.bind(projectId, connectionId, request,
                AdminMcpController.userId(jwt)));
    }

    @DeleteMapping("/{connectionId}") public ResponseEntity<Void> unbind(
            @PathVariable UUID projectId, @PathVariable UUID connectionId,
            @AuthenticationPrincipal Jwt jwt) {
        service.unbind(projectId, connectionId, AdminMcpController.userId(jwt));
        return ResponseEntity.noContent().build();
    }
}
