package com.shitulelv.aicollab.work.api.controller;

import com.shitulelv.aicollab.common.api.ApiResponse;
import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.work.application.service.WorkVisualizationService;
import com.shitulelv.aicollab.work.application.view.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/visualization")
public class WorkVisualizationController {
    private final WorkVisualizationService visualization;

    public WorkVisualizationController(WorkVisualizationService visualization) {
        this.visualization = visualization;
    }

    @GetMapping("/gantt")
    public ApiResponse<GanttView> getGanttData(
            @PathVariable UUID projectId,
            @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.success(visualization.getGanttData(projectId, userId(jwt)));
    }

    @GetMapping("/dependency-graph")
    public ApiResponse<DependencyGraphView> getDependencyGraph(
            @PathVariable UUID projectId,
            @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.success(visualization.getDependencyGraph(projectId, userId(jwt)));
    }

    @GetMapping("/calendar")
    public ApiResponse<CalendarView> getCalendarData(
            @PathVariable UUID projectId,
            @RequestParam int year,
            @RequestParam int month,
            @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.success(visualization.getCalendarData(projectId, year, month, userId(jwt)));
    }

    @GetMapping("/member-load")
    public ApiResponse<MemberLoadView> getMemberLoad(
            @PathVariable UUID projectId,
            @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.success(visualization.getMemberLoad(projectId, userId(jwt)));
    }

    private static UUID userId(Jwt jwt) {
        try { return UUID.fromString(jwt.getSubject()); }
        catch (RuntimeException exception) { throw new BusinessException(ErrorCode.AUTH_UNAUTHORIZED); }
    }
}
