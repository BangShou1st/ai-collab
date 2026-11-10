package com.shitulelv.aicollab.agent.api.controller;

import com.shitulelv.aicollab.agent.api.dto.AgentMemoryRequest;
import com.shitulelv.aicollab.agent.api.dto.AgentMemorySupersedeRequest;
import com.shitulelv.aicollab.agent.application.AgentMemoryService;
import com.shitulelv.aicollab.agent.application.view.AgentMemoryRevisionView;
import com.shitulelv.aicollab.agent.application.view.AgentMemoryView;
import com.shitulelv.aicollab.common.api.ApiResponse;
import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
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
            @PathVariable UUID id, @RequestParam int version,
            @RequestParam(required=false) String verificationState,
            @RequestParam(required=false) @Size(max=500) String reason, @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.success(service.disable(projectId, id, userId(jwt), version, verificationState, reason));
    }

    /** SUPERSEDE(仅管理员):旧记忆指向替代者,替代后不再进入召回,管理视图仍可见。 */
    @PostMapping("/{id}/supersede") public ApiResponse<AgentMemoryView> supersede(@PathVariable UUID projectId,
            @PathVariable UUID id, @Valid @RequestBody AgentMemorySupersedeRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.success(service.supersede(projectId, id, userId(jwt),
                request.supersededBy(), request.reason()));
    }

    /** 修订历史(管理追溯):REVISE/SUPERSEDE/DISABLE 的 before/after 快照与原因。 */
    @GetMapping("/{id}/revisions") public ApiResponse<List<AgentMemoryRevisionView>> revisions(
            @PathVariable UUID projectId, @PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.success(service.revisions(projectId, id, userId(jwt)));
    }
    private static UUID userId(Jwt jwt) {
        try { return UUID.fromString(jwt.getSubject()); }
        catch (RuntimeException exception) { throw new BusinessException(ErrorCode.AUTH_UNAUTHORIZED); }
    }
}
