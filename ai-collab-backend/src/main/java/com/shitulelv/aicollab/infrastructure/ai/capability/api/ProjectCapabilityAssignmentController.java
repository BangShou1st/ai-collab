package com.shitulelv.aicollab.infrastructure.ai.capability.api;

import com.shitulelv.aicollab.common.api.ApiResponse;
import com.shitulelv.aicollab.infrastructure.ai.capability.CapabilityAssignmentService;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/ai/capability-assignments")
public class ProjectCapabilityAssignmentController {

    private final CapabilityAssignmentService service;

    public ProjectCapabilityAssignmentController(CapabilityAssignmentService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<List<CapabilityAssignmentView>> list(@PathVariable UUID projectId,
            @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.success(service.listProject(projectId, userId(jwt)));
    }

    @PostMapping
    public ApiResponse<CapabilityAssignmentView> upsert(@PathVariable UUID projectId,
            @Valid @RequestBody CapabilityAssignmentRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.success(service.upsertProject(projectId, userId(jwt), request));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable UUID projectId, @PathVariable UUID id,
            @AuthenticationPrincipal Jwt jwt) {
        service.deleteProject(projectId, userId(jwt), id);
        return ApiResponse.success(null);
    }

    private static UUID userId(Jwt jwt) {
        if (jwt == null || jwt.getSubject() == null) {
            throw new com.shitulelv.aicollab.common.exception.BusinessException(
                    com.shitulelv.aicollab.common.exception.ErrorCode.AUTH_UNAUTHORIZED);
        }
        try {
            return UUID.fromString(jwt.getSubject());
        } catch (IllegalArgumentException invalid) {
            throw new com.shitulelv.aicollab.common.exception.BusinessException(
                    com.shitulelv.aicollab.common.exception.ErrorCode.AUTH_UNAUTHORIZED);
        }
    }
}
