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
@RequestMapping("/api/v1/admin/agent/mcp-connections")
public class AdminMcpController {
    private final McpAdministrationService service;
    public AdminMcpController(McpAdministrationService service) { this.service = service; }

    @GetMapping public ApiResponse<List<McpConnectionView>> list(@AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.success(service.list(userId(jwt)));
    }

    @PostMapping public ResponseEntity<ApiResponse<McpConnectionView>> create(
            @Valid @RequestBody McpConnectionRequest request, @AuthenticationPrincipal Jwt jwt) {
        McpConnectionView result = service.create(request, userId(jwt));
        return ResponseEntity.created(URI.create("/api/v1/admin/agent/mcp-connections/" + result.id()))
                .body(ApiResponse.success(result));
    }

    @PatchMapping("/{id}") public ApiResponse<McpConnectionView> update(
            @PathVariable UUID id, @Valid @RequestBody McpConnectionRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.success(service.update(id, request, userId(jwt)));
    }

    @PostMapping("/{id}/test") public ApiResponse<McpConnectionView> test(
            @PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.success(service.test(id, userId(jwt)));
    }

    @PostMapping("/{id}/discover") public ApiResponse<McpConnectionView> discover(
            @PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.success(service.discover(id, userId(jwt)));
    }

    @PostMapping("/{id}/enable") public ApiResponse<McpConnectionView> enable(
            @PathVariable UUID id, @RequestParam int version, @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.success(service.setEnabled(id, version, true, userId(jwt)));
    }

    @PostMapping("/{id}/disable") public ApiResponse<McpConnectionView> disable(
            @PathVariable UUID id, @RequestParam int version, @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.success(service.setEnabled(id, version, false, userId(jwt)));
    }

    static UUID userId(Jwt jwt) {
        try { return UUID.fromString(jwt.getSubject()); }
        catch (RuntimeException exception) { throw new BusinessException(ErrorCode.AUTH_UNAUTHORIZED); }
    }
}
