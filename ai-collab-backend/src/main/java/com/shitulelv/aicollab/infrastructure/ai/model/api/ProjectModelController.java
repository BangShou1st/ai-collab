package com.shitulelv.aicollab.infrastructure.ai.model.api;

import com.shitulelv.aicollab.common.api.ApiResponse;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelPurpose;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelConfigurationRepository;
import com.shitulelv.aicollab.infrastructure.ai.model.ProjectModelConfigurationService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * 项目级模型配置接口（V2 已废弃：个人 AI 配置见 /api/v1/user/ai-providers）。
 * 仅保留只读兼容，运行时不再路由到此处；物理清理见未来独立 migration。
 */
@Deprecated
@RestController
@RequestMapping("/api/v1/projects/{projectId}/models")
public class ProjectModelController {
    private final ProjectModelConfigurationService models;

    public ProjectModelController(ProjectModelConfigurationService models) {
        this.models = models;
    }

    @GetMapping
    public ApiResponse<List<ModelConfigurationView>> list(
            @PathVariable UUID projectId,
            @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.success(models.list(projectId, userId(jwt)));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ModelConfigurationView> create(
            @PathVariable UUID projectId,
            @Valid @RequestBody ModelConfigurationRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.success(models.create(projectId, request, userId(jwt)));
    }

    @PutMapping("/{id}")
    public ApiResponse<ModelConfigurationView> update(
            @PathVariable UUID projectId,
            @PathVariable UUID id,
            @Valid @RequestBody ModelConfigurationRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.success(models.update(projectId, id, request, userId(jwt)));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(
            @PathVariable UUID projectId,
            @PathVariable UUID id,
            @AuthenticationPrincipal Jwt jwt) {
        models.delete(projectId, id, userId(jwt));
    }

    @PostMapping("/{id}/test")
    public ApiResponse<Void> test(
            @PathVariable UUID projectId,
            @PathVariable UUID id,
            @AuthenticationPrincipal Jwt jwt) {
        models.test(projectId, id, userId(jwt));
        return ApiResponse.success(null);
    }

    @GetMapping("/assignments")
    public ApiResponse<List<ModelConfigurationRepository.ModelAssignment>> assignments(
            @PathVariable UUID projectId,
            @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.success(models.assignments(projectId, userId(jwt)));
    }

    @PutMapping("/assignments/{purpose}")
    public ApiResponse<Void> assign(
            @PathVariable UUID projectId,
            @PathVariable ModelPurpose purpose,
            @RequestBody AssignModelRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        models.assign(projectId, purpose, request.configurationId(), userId(jwt));
        return ApiResponse.success(null);
    }

    private static UUID userId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }

    public record AssignModelRequest(UUID configurationId) {
    }
}
