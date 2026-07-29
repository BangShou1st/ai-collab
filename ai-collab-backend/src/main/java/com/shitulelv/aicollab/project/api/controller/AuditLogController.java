package com.shitulelv.aicollab.project.api.controller;

import com.shitulelv.aicollab.common.api.ApiResponse;
import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.project.application.service.AuditLogQueryService;
import com.shitulelv.aicollab.project.application.view.AuditLogPageView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/audit-logs")
public class AuditLogController {
    private final AuditLogQueryService auditLogQuery;

    public AuditLogController(AuditLogQueryService auditLogQuery) {
        this.auditLogQuery = auditLogQuery;
    }

    @GetMapping
    public ApiResponse<AuditLogPageView> page(
            @PathVariable UUID projectId,
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        page = Math.max(1, page);
        size = Math.max(1, Math.min(100, size));
        return ApiResponse.success(auditLogQuery.page(projectId, userId(jwt), page, size));
    }

    private static UUID userId(Jwt jwt) {
        try {
            return UUID.fromString(jwt.getSubject());
        } catch (RuntimeException exception) {
            throw new BusinessException(ErrorCode.AUTH_UNAUTHORIZED);
        }
    }
}
