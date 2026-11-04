package com.shitulelv.aicollab.notification.api.controller;

import com.shitulelv.aicollab.common.api.ApiResponse;
import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.notification.application.service.AdminNotificationService;
import com.shitulelv.aicollab.notification.infrastructure.entity.AdminNotification;
import com.shitulelv.aicollab.user.service.UserService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/notifications")
public class AdminNotificationController {
    private final AdminNotificationService notificationService;
    private final UserService userService;

    public AdminNotificationController(AdminNotificationService notificationService, UserService userService) {
        this.notificationService = notificationService;
        this.userService = userService;
    }

    @GetMapping
    public ApiResponse<List<AdminNotification>> list(
            @RequestParam(required = false, defaultValue = "false") boolean unreadOnly,
            @RequestParam(required = false, defaultValue = "50") int limit,
            @AuthenticationPrincipal Jwt jwt) {
        checkAdmin(jwt);
        return ApiResponse.success(notificationService.list(unreadOnly, limit));
    }

    @GetMapping("/unread-count")
    public ApiResponse<Map<String, Integer>> unreadCount(@AuthenticationPrincipal Jwt jwt) {
        checkAdmin(jwt);
        return ApiResponse.success(Map.of("unreadCount", notificationService.countUnread()));
    }

    @PostMapping("/{id}/read")
    public ApiResponse<Void> markAsRead(
            @PathVariable UUID id,
            @AuthenticationPrincipal Jwt jwt) {
        checkAdmin(jwt);
        notificationService.markAsRead(id);
        return ApiResponse.success(null);
    }

    @PostMapping("/read-all")
    public ApiResponse<Void> markAllAsRead(@AuthenticationPrincipal Jwt jwt) {
        checkAdmin(jwt);
        notificationService.markAllAsRead();
        return ApiResponse.success(null);
    }

    private void checkAdmin(Jwt jwt) {
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
    }
}
