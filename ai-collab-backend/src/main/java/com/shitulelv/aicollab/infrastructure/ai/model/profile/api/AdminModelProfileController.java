package com.shitulelv.aicollab.infrastructure.ai.model.profile.api;

import com.shitulelv.aicollab.common.api.ApiResponse;
import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.infrastructure.ai.model.profile.ModelProfileService;
import com.shitulelv.aicollab.user.service.UserService;
import jakarta.validation.Valid;
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

import java.util.List;
import java.util.UUID;

/** 管理员维护全局模型窗口档案（默认不开放给普通用户，避免把用户声明当成模型事实）。 */
@RestController
@RequestMapping("/api/v1/admin/ai/model-profiles")
public class AdminModelProfileController {
    private final ModelProfileService service;
    private final UserService userService;

    public AdminModelProfileController(ModelProfileService service, UserService userService) {
        this.service = service;
        this.userService = userService;
    }

    @GetMapping
    public ApiResponse<List<ModelProfileView>> list(@AuthenticationPrincipal Jwt jwt) {
        checkAdmin(jwt);
        return ApiResponse.success(service.list());
    }

    @PostMapping
    public ApiResponse<ModelProfileView> create(@Valid @RequestBody ModelProfileRequest request,
                                                @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.success(service.create(checkAdmin(jwt), request));
    }

    @PutMapping("/{id}")
    public ApiResponse<ModelProfileView> update(@PathVariable UUID id,
                                                @Valid @RequestBody ModelProfileRequest request,
                                                @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.success(service.update(checkAdmin(jwt), id, request));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        checkAdmin(jwt);
        service.delete(id);
        return ApiResponse.success(null);
    }

    private UUID checkAdmin(Jwt jwt) {
        if (jwt == null || jwt.getSubject() == null) {
            throw new BusinessException(ErrorCode.AUTH_UNAUTHORIZED);
        }
        UUID userId;
        try {
            userId = UUID.fromString(jwt.getSubject());
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(ErrorCode.AUTH_UNAUTHORIZED);
        }
        userService.requireSystemAdmin(userId);
        return userId;
    }
}
