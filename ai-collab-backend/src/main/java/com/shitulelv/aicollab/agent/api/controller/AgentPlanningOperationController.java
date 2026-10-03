package com.shitulelv.aicollab.agent.api.controller;
import com.shitulelv.aicollab.agent.application.AgentPlanningOperationService;
import com.shitulelv.aicollab.common.api.ApiResponse;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import java.util.UUID;
@RestController
@RequestMapping("/api/v1/projects/{projectId}/agent")
public class AgentPlanningOperationController {
    private final AgentPlanningOperationService operations;
    public AgentPlanningOperationController(AgentPlanningOperationService operations){this.operations=operations;}
    @GetMapping("/sessions/{sessionId}/planning-operations") public ApiResponse<?> list(@PathVariable UUID projectId,@PathVariable UUID sessionId,@AuthenticationPrincipal Jwt jwt){return ApiResponse.success(operations.list(projectId,sessionId,UUID.fromString(jwt.getSubject())));}
    @GetMapping("/planning-operations/{operationId}") public ApiResponse<?> get(@PathVariable UUID projectId,@PathVariable UUID operationId,@AuthenticationPrincipal Jwt jwt){return ApiResponse.success(operations.get(projectId,operationId,UUID.fromString(jwt.getSubject())));}
}
