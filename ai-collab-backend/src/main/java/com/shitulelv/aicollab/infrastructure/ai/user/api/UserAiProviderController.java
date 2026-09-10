package com.shitulelv.aicollab.infrastructure.ai.user.api;

import com.shitulelv.aicollab.common.api.ApiResponse;
import com.shitulelv.aicollab.infrastructure.ai.ChatCompletionResult;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelPurpose;
import com.shitulelv.aicollab.infrastructure.ai.user.UserAiProviderService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * 个人 AI Provider 接口：Credential 归属永远是当前登录用户。
 */
@RestController
@RequestMapping("/api/v1/user/ai-providers")
public class UserAiProviderController {
    private final UserAiProviderService providers;

    public UserAiProviderController(UserAiProviderService providers) {
        this.providers = providers;
    }

    @GetMapping
    public ApiResponse<List<UserAiProviderView>> list(@AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.success(providers.list(userId(jwt)));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<UserAiProviderView> create(
            @Valid @RequestBody UserAiProviderRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.success(providers.create(userId(jwt), request));
    }

    @PatchMapping("/{id}")
    public ApiResponse<UserAiProviderView> update(
            @PathVariable UUID id,
            @Valid @RequestBody UserAiProviderRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.success(providers.update(userId(jwt), id, request));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        providers.delete(userId(jwt), id);
    }

    @PostMapping("/{id}/test")
    public ApiResponse<ChatCompletionResult> test(
            @PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.success(providers.test(userId(jwt), id));
    }

    @PutMapping("/defaults")
    public ApiResponse<Void> setDefault(
            @RequestBody SetDefaultRequest request, @AuthenticationPrincipal Jwt jwt) {
        providers.setDefault(userId(jwt), request.providerId());
        return ApiResponse.success(null);
    }

    @GetMapping("/purposes")
    public ApiResponse<java.util.Map<ModelPurpose, UUID>> listPurposes(@AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.success(providers.listAssignments(userId(jwt)));
    }

    @PutMapping("/purposes/{purpose}")
    public ApiResponse<Void> assignPurpose(
            @PathVariable ModelPurpose purpose,
            @RequestBody AssignPurposeRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        providers.assignPurpose(userId(jwt), purpose, request.providerId());
        return ApiResponse.success(null);
    }

    @DeleteMapping("/purposes/{purpose}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unassignPurpose(
            @PathVariable ModelPurpose purpose, @AuthenticationPrincipal Jwt jwt) {
        providers.unassignPurpose(userId(jwt), purpose);
    }

    private static UUID userId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }

    public record SetDefaultRequest(UUID providerId) {
    }

    public record AssignPurposeRequest(UUID providerId) {
    }
}
