package com.shitulelv.aicollab.infrastructure.ai.model.api;

import com.shitulelv.aicollab.common.api.ApiResponse;
import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.infrastructure.ai.ChatCompletionResult;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelConfigurationRepository;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelConfigurationService;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelPurpose;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/models")
public class AdminModelController {
    private final ModelConfigurationService models;

    public AdminModelController(ModelConfigurationService models) {
        this.models = models;
    }

    @GetMapping
    public ApiResponse<List<ModelConfigurationView>> list(@AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.success(models.list(userId(jwt)));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<ModelConfigurationView>> create(
            @Valid @RequestBody ModelConfigurationRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        ModelConfigurationView view = models.create(request, userId(jwt));
        return ResponseEntity.created(URI.create("/api/v1/admin/models/" + view.id()))
                .body(ApiResponse.success(view));
    }

    @PutMapping("/{id}")
    public ApiResponse<ModelConfigurationView> update(
            @PathVariable UUID id,
            @Valid @RequestBody ModelConfigurationRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.success(models.update(id, request, userId(jwt)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
            @PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        models.delete(id, userId(jwt));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/test")
    public ApiResponse<ChatCompletionResult> test(
            @PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.success(models.test(id, userId(jwt)));
    }

    @GetMapping("/assignments")
    public ApiResponse<List<ModelConfigurationRepository.ModelAssignment>> assignments(
            @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.success(models.assignments(userId(jwt)));
    }

    @PutMapping("/assignments/{purpose}")
    public ResponseEntity<Void> assign(
            @PathVariable ModelPurpose purpose,
            @Valid @RequestBody ModelAssignmentRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        models.assign(purpose, request.configurationId(), userId(jwt));
        return ResponseEntity.noContent().build();
    }

    private static UUID userId(Jwt jwt) {
        try {
            return UUID.fromString(jwt.getSubject());
        } catch (RuntimeException exception) {
            throw new BusinessException(ErrorCode.AUTH_UNAUTHORIZED);
        }
    }
}
