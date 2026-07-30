package com.shitulelv.aicollab.notification.api.controller;

import com.shitulelv.aicollab.common.api.ApiResponse;
import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.notification.application.service.NotificationApplicationService;
import com.shitulelv.aicollab.notification.application.view.NotificationView;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {
    private final NotificationApplicationService notifications;

    public NotificationController(NotificationApplicationService notifications) {
        this.notifications = notifications;
    }

    @GetMapping
    public ApiResponse<List<NotificationView>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.success(notifications.list(userId(jwt), page, size));
    }

    @GetMapping("/unread-count")
    public ApiResponse<Map<String, Integer>> unreadCount(@AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.success(Map.of("count", notifications.countUnread(userId(jwt))));
    }

    @GetMapping("/project/{projectId}")
    public ApiResponse<List<NotificationView>> listByProject(
            @PathVariable UUID projectId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.success(notifications.listByProject(projectId, userId(jwt), page, size));
    }

    @PatchMapping("/{notificationId}/read")
    public ResponseEntity<Void> markAsRead(
            @PathVariable UUID notificationId,
            @AuthenticationPrincipal Jwt jwt) {
        notifications.markAsRead(userId(jwt), notificationId);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/read-all")
    public ResponseEntity<Void> markAllAsRead(@AuthenticationPrincipal Jwt jwt) {
        notifications.markAllAsRead(userId(jwt));
        return ResponseEntity.noContent().build();
    }

    private static UUID userId(Jwt jwt) {
        try { return UUID.fromString(jwt.getSubject()); }
        catch (RuntimeException exception) { throw new BusinessException(ErrorCode.AUTH_UNAUTHORIZED); }
    }
}
