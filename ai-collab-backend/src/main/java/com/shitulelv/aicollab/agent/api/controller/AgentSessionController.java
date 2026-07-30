package com.shitulelv.aicollab.agent.api.controller;

import com.shitulelv.aicollab.agent.api.dto.CreateAgentSessionRequest;
import com.shitulelv.aicollab.agent.api.dto.RenameAgentSessionRequest;
import com.shitulelv.aicollab.agent.api.dto.SubmitAgentMessageRequest;
import com.shitulelv.aicollab.agent.api.dto.AgentMessageResponse;
import com.shitulelv.aicollab.agent.api.dto.AgentRunDetailResponse;
import com.shitulelv.aicollab.agent.application.AgentRunService;
import com.shitulelv.aicollab.agent.application.view.*;
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
@RequestMapping("/api/v1/projects/{projectId}/agent")
public class AgentSessionController {
    private final AgentRunService runs;

    public AgentSessionController(AgentRunService runs) {
        this.runs = runs;
    }

    @GetMapping("/sessions")
    public ApiResponse<List<AgentSessionView>> listSessions(
            @PathVariable UUID projectId, @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.success(runs.listSessions(projectId, userId(jwt)));
    }

    @PostMapping("/sessions")
    public ResponseEntity<ApiResponse<AgentSessionView>> createSession(
            @PathVariable UUID projectId,
            @Valid @RequestBody CreateAgentSessionRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        AgentSessionView created = runs.createSession(projectId, userId(jwt), request);
        return ResponseEntity.created(URI.create(
                "/api/v1/projects/" + projectId + "/agent/sessions/" + created.id()))
                .body(ApiResponse.success(created));
    }

    @GetMapping("/sessions/{sessionId}")
    public ApiResponse<AgentSessionView> getSession(
            @PathVariable UUID projectId, @PathVariable UUID sessionId,
            @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.success(runs.getSession(projectId, sessionId, userId(jwt)));
    }

    @PatchMapping("/sessions/{sessionId}")
    public ApiResponse<AgentSessionView> renameSession(
            @PathVariable UUID projectId,
            @PathVariable UUID sessionId,
            @Valid @RequestBody RenameAgentSessionRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.success(runs.renameSession(
                projectId, sessionId, userId(jwt), request));
    }

    @DeleteMapping("/sessions/{sessionId}")
    public ResponseEntity<Void> deleteSession(
            @PathVariable UUID projectId,
            @PathVariable UUID sessionId,
            @AuthenticationPrincipal Jwt jwt) {
        runs.deleteSession(projectId, sessionId, userId(jwt));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/sessions/{sessionId}/messages")
    public ApiResponse<List<AgentMessageResponse>> listMessages(
            @PathVariable UUID projectId, @PathVariable UUID sessionId,
            @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.success(runs.listMessages(projectId, sessionId, userId(jwt))
                .stream().map(AgentMessageResponse::from).toList());
    }

    @PostMapping("/sessions/{sessionId}/messages")
    public ResponseEntity<ApiResponse<AgentRunView>> submit(
            @PathVariable UUID projectId, @PathVariable UUID sessionId,
            @Valid @RequestBody SubmitAgentMessageRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.accepted()
                .body(ApiResponse.success(runs.submit(
                        projectId, sessionId, userId(jwt), request)));
    }

    @GetMapping("/runs/{runId}")
    public ApiResponse<AgentRunDetailResponse> getRun(
            @PathVariable UUID projectId, @PathVariable UUID runId,
            @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.success(AgentRunDetailResponse.from(
                runs.getRun(projectId, runId, userId(jwt))));
    }

    @PostMapping("/runs/{runId}/cancel")
    public ResponseEntity<Void> cancel(
            @PathVariable UUID projectId, @PathVariable UUID runId,
            @AuthenticationPrincipal Jwt jwt) {
        runs.cancel(projectId, runId, userId(jwt));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/runs/{runId}/retry")
    public ApiResponse<AgentRunView> retry(
            @PathVariable UUID projectId, @PathVariable UUID runId,
            @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.success(runs.retry(projectId, runId, userId(jwt)));
    }

    private static UUID userId(Jwt jwt) {
        try {
            return UUID.fromString(jwt.getSubject());
        } catch (RuntimeException exception) {
            throw new BusinessException(ErrorCode.AUTH_UNAUTHORIZED);
        }
    }
}
