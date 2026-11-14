package com.shitulelv.aicollab.work.api.controller;

import com.shitulelv.aicollab.common.exception.GlobalExceptionHandler;
import com.shitulelv.aicollab.work.application.service.WorkReportService;
import com.shitulelv.aicollab.work.application.view.WeeklyReportView;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class WorkReportControllerContractTest {

    private final WorkReportService service = mock(WorkReportService.class);
    private final UUID projectId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void defaultsToCurrentWeekWhenBothDatesAreAbsent() throws Exception {
        WeeklyReportView report = report();
        when(service.getWeeklyReport(projectId, userId)).thenReturn(report);

        jwtAuthentication();
        mockMvc().perform(get("/api/v1/projects/{projectId}/reports/weekly", projectId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reportDate").value("2026-11-15"));
        verify(service).getWeeklyReport(projectId, userId);
    }

    @Test
    void forwardsExplicitPeriod() throws Exception {
        WeeklyReportView report = report();
        LocalDate start = LocalDate.of(2026, 11, 9);
        LocalDate end = LocalDate.of(2026, 11, 15);
        when(service.getWeeklyReport(projectId, userId, start, end)).thenReturn(report);

        jwtAuthentication();
        mockMvc().perform(get("/api/v1/projects/{projectId}/reports/weekly", projectId)
                        .param("startDate", start.toString())
                        .param("endDate", end.toString()))
                .andExpect(status().isOk());
        verify(service).getWeeklyReport(projectId, userId, start, end);
    }

    @Test
    void missingRequiredPlanIdReturnsStableValidationError() throws Exception {
        jwtAuthentication();
        mockMvc().perform(get("/api/v1/projects/{projectId}/reports/plan-comparison", projectId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value("缺少必需请求参数：planId"));
    }

    private MockMvc mockMvc() {
        return MockMvcBuilders.standaloneSetup(new WorkReportController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .build();
    }

    private void jwtAuthentication() {
        Jwt jwt = Jwt.withTokenValue("test-token")
                .header("alg", "none")
                .subject(userId.toString())
                .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }

    private static WeeklyReportView report() {
        return new WeeklyReportView(
                LocalDate.of(2026, 11, 15),
                LocalDate.of(2026, 11, 9),
                LocalDate.of(2026, 11, 15),
                new WeeklyReportView.TaskStatistics(
                        0, 0, 0, 0, BigDecimal.ZERO, 0, 0, 0),
                new WeeklyReportView.MilestoneStatistics(
                        0, 0, 0, 0, 0, 7),
                new WeeklyReportView.PeriodStatistics(0, 0, 0, 0),
                List.of(),
                List.of(),
                List.of(),
                "DATABASE",
                List.of());
    }
}
