package com.shitulelv.aicollab.agent.api.controller;

import com.shitulelv.aicollab.agent.application.AiJobAdminService;
import com.shitulelv.aicollab.agent.application.view.AiJobView;
import com.shitulelv.aicollab.common.api.ApiResponse;
import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Memory Job 的管理入口：创建后进入 QUEUED，由受开关控制的调度器派发。 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/agent/memory-jobs")
public class AgentMemoryJobController {

    private final AiJobAdminService service;

    public AgentMemoryJobController(AiJobAdminService service) {
        this.service = service;
    }

    @PostMapping
    public ApiResponse<AiJobView> create(@PathVariable UUID projectId,
            @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.success(service.create(projectId, userId(jwt)));
    }

    @GetMapping("/{jobId}")
    public ApiResponse<AiJobView> get(@PathVariable UUID projectId, @PathVariable UUID jobId,
            @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.success(service.get(projectId, jobId, userId(jwt)));
    }

    private static UUID userId(Jwt jwt) {
        try {
            return UUID.fromString(jwt.getSubject());
        } catch (RuntimeException exception) {
            throw new BusinessException(ErrorCode.AUTH_UNAUTHORIZED);
        }
    }
}
