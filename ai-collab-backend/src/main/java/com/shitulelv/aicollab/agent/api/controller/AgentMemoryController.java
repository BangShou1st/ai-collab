package com.shitulelv.aicollab.agent.api.controller;

import com.shitulelv.aicollab.agent.api.dto.AgentMemoryRequest;
import com.shitulelv.aicollab.agent.application.AgentMemoryService;
import com.shitulelv.aicollab.agent.application.view.AgentMemoryView;
import com.shitulelv.aicollab.common.api.ApiResponse;
import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/agent/memories")
public class AgentMemoryController {
    private final AgentMemoryService service;
    public AgentMemoryController(AgentMemoryService service) { this.service = service; }
    @GetMapping public ApiResponse<List<AgentMemoryView>> list(@PathVariable UUID projectId,
            @RequestParam(defaultValue="false") boolean activeOnly, @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.success(service.list(projectId, userId(jwt), activeOnly));
    }
    @PostMapping public ApiResponse<AgentMemoryView> create(@PathVariable UUID projectId,
            @Valid @RequestBody AgentMemoryRequest request, @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.success(service.create(projectId, userId(jwt), request));
    }
    @PatchMapping("/{id}") public ApiResponse<AgentMemoryView> update(@PathVariable UUID projectId,
            @PathVariable UUID id, @Valid @RequestBody AgentMemoryRequest request, @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.success(service.update(projectId, id, userId(jwt), request));
    }
    @DeleteMapping("/{id}") public ApiResponse<AgentMemoryView> disable(@PathVariable UUID projectId,
            @PathVariable UUID id, @RequestParam int version, @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.success(service.disable(projectId, id, userId(jwt), version));
    }
    private static UUID userId(Jwt jwt) {
        try { return UUID.fromString(jwt.getSubject()); }
        catch (RuntimeException exception) { throw new BusinessException(ErrorCode.AUTH_UNAUTHORIZED); }
    }
}
