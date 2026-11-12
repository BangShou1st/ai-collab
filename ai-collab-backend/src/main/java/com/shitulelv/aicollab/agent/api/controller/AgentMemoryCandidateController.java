package com.shitulelv.aicollab.agent.api.controller;

import com.shitulelv.aicollab.agent.api.dto.AgentMemoryCandidateDecisionRequest;
import com.shitulelv.aicollab.agent.application.AgentMemoryCandidateService;
import com.shitulelv.aicollab.agent.application.view.AgentMemoryCandidateView;
import com.shitulelv.aicollab.agent.application.view.AgentMemoryView;
import com.shitulelv.aicollab.common.api.ApiResponse;
import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Dreaming 候选审核 API；仅项目管理员可用，批准前不会写入正式记忆。 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/agent/memory-candidates")
public class AgentMemoryCandidateController {

    private final AgentMemoryCandidateService service;

    public AgentMemoryCandidateController(AgentMemoryCandidateService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<List<AgentMemoryCandidateView>> list(@PathVariable UUID projectId,
            @RequestParam(defaultValue = "PENDING_REVIEW") String reviewStatus,
            @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.success(service.list(projectId, userId(jwt), reviewStatus));
    }

    @PostMapping("/{candidateId}/approve")
    public ApiResponse<AgentMemoryView> approve(@PathVariable UUID projectId,
            @PathVariable UUID candidateId,
            @RequestBody(required = false) @Valid AgentMemoryCandidateDecisionRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        String reason = request == null ? null : request.reason();
        return ApiResponse.success(service.approve(projectId, candidateId, userId(jwt), reason));
    }

    @PostMapping("/{candidateId}/reject")
    public ApiResponse<AgentMemoryCandidateView> reject(@PathVariable UUID projectId,
            @PathVariable UUID candidateId,
            @RequestBody(required = false) @Valid AgentMemoryCandidateDecisionRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        String reason = request == null ? null : request.reason();
        return ApiResponse.success(service.reject(projectId, candidateId, userId(jwt), reason));
    }

    private static UUID userId(Jwt jwt) {
        try {
            return UUID.fromString(jwt.getSubject());
        } catch (RuntimeException exception) {
            throw new BusinessException(ErrorCode.AUTH_UNAUTHORIZED);
        }
    }
}
