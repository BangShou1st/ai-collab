package com.shitulelv.aicollab.agent.infrastructure.mcp.api;

import com.shitulelv.aicollab.agent.infrastructure.mcp.McpAdministrationService;
import com.shitulelv.aicollab.common.api.ApiResponse;
import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/agent/mcp-connections")
public class ProjectMcpController {
    private final McpAdministrationService service;
    public ProjectMcpController(McpAdministrationService service) { this.service = service; }

    @GetMapping public ApiResponse<List<McpConnectionView>> list(
            @PathVariable UUID projectId, @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.success(service.list(projectId, userId(jwt)));
    }

    @PostMapping public ResponseEntity<ApiResponse<McpConnectionView>> create(
            @PathVariable UUID projectId,
            @Valid @RequestBody McpConnectionRequest request, @AuthenticationPrincipal Jwt jwt) {
        McpConnectionView result = service.create(projectId, request, userId(jwt));
        return ResponseEntity.created(URI.create("/api/v1/projects/" + projectId + "/agent/mcp-connections/" + result.id()))
                .body(ApiResponse.success(result));
    }

    @PatchMapping("/{id}") public ApiResponse<McpConnectionView> update(
            @PathVariable UUID projectId, @PathVariable UUID id,
            @Valid @RequestBody McpConnectionRequest request, @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.success(service.update(projectId, id, request, userId(jwt)));
    }

    @PostMapping("/{id}/test") public ApiResponse<McpConnectionView> test(
            @PathVariable UUID projectId, @PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.success(service.test(projectId, id, userId(jwt)));
    }

    @PostMapping("/{id}/discover") public ApiResponse<McpConnectionView> discover(
            @PathVariable UUID projectId, @PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.success(service.discover(projectId, id, userId(jwt)));
    }

    @PostMapping("/{id}/enable") public ApiResponse<McpConnectionView> enable(
            @PathVariable UUID projectId, @PathVariable UUID id,
            @RequestParam int version, @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.success(service.setEnabled(projectId, id, version, true, userId(jwt)));
    }

    @PostMapping("/{id}/disable") public ApiResponse<McpConnectionView> disable(
            @PathVariable UUID projectId, @PathVariable UUID id,
            @RequestParam int version, @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.success(service.setEnabled(projectId, id, version, false, userId(jwt)));
    }

    static UUID userId(Jwt jwt) {
        try { return UUID.fromString(jwt.getSubject()); }
        catch (RuntimeException exception) { throw new BusinessException(ErrorCode.AUTH_UNAUTHORIZED); }
    }
}
