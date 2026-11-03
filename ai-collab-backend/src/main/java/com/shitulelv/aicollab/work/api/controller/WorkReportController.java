package com.shitulelv.aicollab.work.api.controller;

import com.shitulelv.aicollab.common.api.ApiResponse;
import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.work.application.service.WorkReportService;
import com.shitulelv.aicollab.work.application.view.WeeklyReportView;
import com.shitulelv.aicollab.work.application.view.RiskAnalysisView;
import com.shitulelv.aicollab.work.application.view.PlanComparisonView;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/reports")
public class WorkReportController {
    private final WorkReportService reports;

    public WorkReportController(WorkReportService reports) {
        this.reports = reports;
    }

    /**
     * AI 周报确定性统计。默认统计周期为本周（周一起始，含今天）；
     * 可选传 startDate/endDate（ISO yyyy-MM-dd，含首尾，跨度 ≤ 62 天）显式指定周期。
     */
    @GetMapping("/weekly")
    public ApiResponse<WeeklyReportView> getWeeklyReport(
            @PathVariable UUID projectId,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.success(reports.getWeeklyReport(projectId, userId(jwt), startDate, endDate));
    }

    @GetMapping("/risks")
    public ApiResponse<RiskAnalysisView> getRiskAnalysis(
            @PathVariable UUID projectId,
            @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.success(reports.getRiskAnalysis(projectId, userId(jwt)));
    }

    @GetMapping("/plan-comparison")
    public ApiResponse<PlanComparisonView> getPlanComparison(
            @PathVariable UUID projectId,
            @RequestParam UUID planId,
            @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.success(reports.getPlanComparison(projectId, planId, userId(jwt)));
    }

    private static UUID userId(Jwt jwt) {
        try { return UUID.fromString(jwt.getSubject()); }
        catch (RuntimeException exception) { throw new BusinessException(ErrorCode.AUTH_UNAUTHORIZED); }
    }
}
