package com.shitulelv.aicollab.planning.api;

import com.shitulelv.aicollab.common.api.ApiResponse;
import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.planning.application.TaskPlanCommandService;
import com.shitulelv.aicollab.planning.application.TaskPlanConfirmationService;
import com.shitulelv.aicollab.planning.application.TaskPlanQueryService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/ai/task-plans")
public class TaskPlanController {
    private final TaskPlanCommandService commands;
    private final TaskPlanQueryService queries;
    private final TaskPlanConfirmationService confirmations;
    public TaskPlanController(TaskPlanCommandService commands, TaskPlanQueryService queries,
                              TaskPlanConfirmationService confirmations) {
        this.commands = commands; this.queries = queries; this.confirmations = confirmations;
    }

    @GetMapping public ApiResponse<?> list(@PathVariable UUID projectId,
            @RequestParam(required=false) String status, @RequestParam(defaultValue="0") int page,
            @RequestParam(defaultValue="20") int size, @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.success(queries.list(projectId, status, page, size, userId(jwt)));
    }
    @PostMapping public ResponseEntity<ApiResponse<?>> create(@PathVariable UUID projectId,
            @Valid @RequestBody CreateTaskPlanRequest request, @AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.accepted().body(ApiResponse.success(commands.create(projectId, request, userId(jwt))));
    }
    @GetMapping("/{planId}") public ApiResponse<?> detail(@PathVariable UUID projectId,
            @PathVariable UUID planId, @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.success(queries.detail(projectId, planId, userId(jwt)));
    }
    @PostMapping("/{planId}/cancel") public ResponseEntity<ApiResponse<?>> cancel(
            @PathVariable UUID projectId, @PathVariable UUID planId, @AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.accepted().body(ApiResponse.success(commands.cancel(projectId, planId, userId(jwt))));
    }
    @PostMapping("/{planId}/retry-detail") public ResponseEntity<ApiResponse<?>> retry(
            @PathVariable UUID projectId, @PathVariable UUID planId, @AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.accepted().body(ApiResponse.success(commands.retryDetail(projectId, planId, userId(jwt))));
    }
    @PostMapping("/{planId}/regenerate") public ResponseEntity<ApiResponse<?>> regenerate(
            @PathVariable UUID projectId, @PathVariable UUID planId, @AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.accepted().body(ApiResponse.success(commands.regenerate(projectId, planId, userId(jwt))));
    }
    @GetMapping("/{planId}/versions") public ApiResponse<?> versions(@PathVariable UUID projectId,
            @PathVariable UUID planId, @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.success(queries.versions(projectId, planId, userId(jwt)));
    }
    @GetMapping("/{planId}/versions/{versionId}") public ApiResponse<?> version(@PathVariable UUID projectId,
            @PathVariable UUID planId, @PathVariable UUID versionId, @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.success(queries.version(projectId, planId, versionId, userId(jwt)));
    }
    @PostMapping("/{planId}/versions") public ApiResponse<?> save(@PathVariable UUID projectId,
            @PathVariable UUID planId, @Valid @RequestBody SaveTaskPlanVersionRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.success(Map.of("versionId", commands.save(projectId, planId, request, userId(jwt))));
    }
    @PostMapping("/{planId}/versions/{versionId}/restore") public ApiResponse<?> restore(
            @PathVariable UUID projectId, @PathVariable UUID planId, @PathVariable UUID versionId,
            @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.success(Map.of("versionId", commands.restore(projectId, planId, versionId, userId(jwt))));
    }
    @PostMapping("/{planId}/confirm") public ResponseEntity<ApiResponse<?>> confirm(@PathVariable UUID projectId,
            @PathVariable UUID planId, @RequestHeader("Idempotency-Key") UUID key,
            @Valid @RequestBody ConfirmTaskPlanRequest request, @AuthenticationPrincipal Jwt jwt) {
        Map<String, Object> result = confirmations.confirm(
                projectId, planId, request.versionId(), key, userId(jwt));
        ApiResponse<?> body = ApiResponse.success(result);
        return "PROCESSING".equals(result.get("status"))
                ? ResponseEntity.accepted().body(body) : ResponseEntity.ok(body);
    }
    @DeleteMapping("/{planId}") public ResponseEntity<Void> delete(@PathVariable UUID projectId,
            @PathVariable UUID planId, @AuthenticationPrincipal Jwt jwt) {
        commands.delete(projectId, planId, userId(jwt)); return ResponseEntity.noContent().build();
    }
    private static UUID userId(Jwt jwt) {
        try { return UUID.fromString(jwt.getSubject()); }
        catch (RuntimeException exception) { throw new BusinessException(ErrorCode.AUTH_UNAUTHORIZED); }
    }
}
