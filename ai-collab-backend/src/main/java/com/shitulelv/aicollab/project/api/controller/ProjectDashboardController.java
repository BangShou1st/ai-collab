package com.shitulelv.aicollab.project.api.controller;

import com.shitulelv.aicollab.common.api.ApiResponse;
import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.project.application.service.ProjectDashboardQueryService;
import com.shitulelv.aicollab.project.application.view.DashboardView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/dashboard")
public class ProjectDashboardController {
    private final ProjectDashboardQueryService dashboardQuery;

    public ProjectDashboardController(ProjectDashboardQueryService dashboardQuery) {
        this.dashboardQuery = dashboardQuery;
    }

    @GetMapping
    public ApiResponse<DashboardView> get(
            @PathVariable UUID projectId,
            @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.success(dashboardQuery.getDashboard(projectId, userId(jwt)));
    }

    private static UUID userId(Jwt jwt) {
        try {
            return UUID.fromString(jwt.getSubject());
        } catch (RuntimeException exception) {
            throw new BusinessException(ErrorCode.AUTH_UNAUTHORIZED);
        }
    }
}
