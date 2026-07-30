package com.shitulelv.aicollab.user.controller;

import com.shitulelv.aicollab.common.api.ApiResponse;
import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.user.dto.CreateTestUserRequest;
import com.shitulelv.aicollab.user.service.AdminService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.Map;
import java.util.List;
import java.util.UUID;
import com.shitulelv.aicollab.user.dto.AdminUserView;

@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {
    private final AdminService adminService;

    public AdminController(AdminService adminService) {
        this.adminService = adminService;
    }

    @GetMapping("/users")
    public ApiResponse<List<AdminUserView>> listUsers(@AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.success(adminService.listUsers(userId(jwt)));
    }

    @PostMapping("/test-users")
    public ResponseEntity<ApiResponse<Map<String, UUID>>> createTestUser(
            @Valid @RequestBody CreateTestUserRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        UUID userId = adminService.createTestUser(
                request.username(),
                request.displayName(),
                request.password(),
                userId(jwt));
        return ResponseEntity.created(URI.create("/api/v1/admin/test-users/" + userId))
                .body(ApiResponse.success(Map.of("userId", userId)));
    }

    @PostMapping("/users/{userId}/disable")
    public ResponseEntity<Void> disableUser(
            @PathVariable UUID userId,
            @AuthenticationPrincipal Jwt jwt) {
        adminService.disableUser(userId, userId(jwt));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/users/{userId}/enable")
    public ResponseEntity<Void> enableUser(
            @PathVariable UUID userId,
            @AuthenticationPrincipal Jwt jwt) {
        adminService.enableUser(userId, userId(jwt));
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
