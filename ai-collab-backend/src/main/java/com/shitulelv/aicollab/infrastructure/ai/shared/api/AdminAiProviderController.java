package com.shitulelv.aicollab.infrastructure.ai.shared.api;

import com.shitulelv.aicollab.common.api.ApiResponse;
import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.infrastructure.ai.ChatCompletionResult;
import com.shitulelv.aicollab.infrastructure.ai.shared.AdminAiProviderService;
import com.shitulelv.aicollab.infrastructure.ai.user.api.UserAiProviderRequest;
import com.shitulelv.aicollab.user.service.UserService;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/ai/providers")
public class AdminAiProviderController {
    private final AdminAiProviderService adminAiService;
    private final UserService userService;

    public AdminAiProviderController(AdminAiProviderService adminAiService, UserService userService) {
        this.adminAiService = adminAiService;
        this.userService = userService;
    }

    @GetMapping
    public ApiResponse<List<AdminSharedAiProviderView>> list(@AuthenticationPrincipal Jwt jwt) {
        UUID adminId = checkAdmin(jwt);
        return ApiResponse.success(adminAiService.listAdminProviders(adminId));
    }

    @PostMapping
    public ApiResponse<AdminSharedAiProviderView> create(
            @Valid @RequestBody UserAiProviderRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        UUID adminId = checkAdmin(jwt);
        return ApiResponse.success(adminAiService.create(adminId, request));
    }

    @PutMapping("/{id}")
    public ApiResponse<AdminSharedAiProviderView> update(
            @PathVariable UUID id,
            @Valid @RequestBody UserAiProviderRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        UUID adminId = checkAdmin(jwt);
        return ApiResponse.success(adminAiService.update(adminId, id, request));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(
            @PathVariable UUID id,
            @AuthenticationPrincipal Jwt jwt) {
        UUID adminId = checkAdmin(jwt);
        adminAiService.delete(adminId, id);
        return ApiResponse.success(null);
    }

    @PostMapping("/{id}/test")
    public ApiResponse<ChatCompletionResult> test(
            @PathVariable UUID id,
            @AuthenticationPrincipal Jwt jwt) {
        UUID adminId = checkAdmin(jwt);
        return ApiResponse.success(adminAiService.test(adminId, id));
    }

    @PostMapping("/{id}/publish")
    public ApiResponse<AdminSharedAiProviderView> publishShared(
            @PathVariable UUID id,
            @AuthenticationPrincipal Jwt jwt) {
        UUID adminId = checkAdmin(jwt);
        return ApiResponse.success(adminAiService.publishShared(adminId, id));
    }

    @PostMapping("/{id}/revoke")
    public ApiResponse<AdminSharedAiProviderView> revokeShared(
            @PathVariable UUID id,
            @AuthenticationPrincipal Jwt jwt) {
        UUID adminId = checkAdmin(jwt);
        return ApiResponse.success(adminAiService.revokeShared(adminId, id));
    }

    private UUID checkAdmin(Jwt jwt) {
        if (jwt == null || jwt.getSubject() == null) {
            throw new BusinessException(ErrorCode.AUTH_UNAUTHORIZED);
        }
        UUID userId;
        try {
            userId = UUID.fromString(jwt.getSubject());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.AUTH_UNAUTHORIZED);
        }
        userService.requireSystemAdmin(userId);
        return userId;
    }
}
