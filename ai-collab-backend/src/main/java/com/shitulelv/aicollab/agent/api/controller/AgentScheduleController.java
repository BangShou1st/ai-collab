package com.shitulelv.aicollab.agent.api.controller;

import com.shitulelv.aicollab.agent.api.dto.CreateAgentScheduleRequest;
import com.shitulelv.aicollab.agent.application.AgentScheduleService;
import com.shitulelv.aicollab.agent.application.view.AgentScheduleView;
import com.shitulelv.aicollab.common.api.ApiResponse;
import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/agent/schedules")
public class AgentScheduleController {
    private final AgentScheduleService schedules;

    public AgentScheduleController(AgentScheduleService schedules) {
        this.schedules = schedules;
    }

    @GetMapping
    public ApiResponse<List<AgentScheduleView>> list(
            @PathVariable UUID projectId, @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.success(schedules.list(projectId, userId(jwt)));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<AgentScheduleView>> create(
            @PathVariable UUID projectId, @Valid @RequestBody CreateAgentScheduleRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        AgentScheduleView created = schedules.create(projectId, userId(jwt), request);
        return ResponseEntity.created(URI.create(
                "/api/v1/projects/" + projectId + "/agent/schedules/" + created.id()))
                .body(ApiResponse.success(created));
    }

    @PostMapping("/{scheduleId}/enable")
    public ApiResponse<AgentScheduleView> enable(
            @PathVariable UUID projectId, @PathVariable UUID scheduleId,
            @RequestParam int version, @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.success(schedules.setEnabled(
                projectId, scheduleId, userId(jwt), version, true));
    }

    @PostMapping("/{scheduleId}/disable")
    public ApiResponse<AgentScheduleView> disable(
            @PathVariable UUID projectId, @PathVariable UUID scheduleId,
            @RequestParam int version, @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.success(schedules.setEnabled(
                projectId, scheduleId, userId(jwt), version, false));
    }

    private static UUID userId(Jwt jwt) {
        try { return UUID.fromString(jwt.getSubject()); }
        catch (RuntimeException exception) {
            throw new BusinessException(ErrorCode.AUTH_UNAUTHORIZED);
        }
    }
}
