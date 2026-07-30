package com.shitulelv.aicollab.work.api.controller;

import com.shitulelv.aicollab.common.api.ApiResponse;
import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.work.api.dto.BatchUpdateTasksRequest;
import com.shitulelv.aicollab.work.api.dto.CreateTaskRequest;
import com.shitulelv.aicollab.work.api.dto.ReplaceDependenciesRequest;
import com.shitulelv.aicollab.work.api.dto.UpdateTaskRequest;
import com.shitulelv.aicollab.work.application.service.TaskApplicationService;
import com.shitulelv.aicollab.work.application.view.TaskView;
import com.shitulelv.aicollab.work.domain.model.TaskStatus;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/tasks")
public class TaskController {
    private final TaskApplicationService tasks;
    public TaskController(TaskApplicationService tasks) { this.tasks = tasks; }

    @GetMapping
    public ApiResponse<List<TaskView>> list(
            @PathVariable UUID projectId,
            @RequestParam(required = false) TaskStatus status,
            @RequestParam(required = false) UUID assigneeId,
            @RequestParam(required = false) UUID milestoneId,
            @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.success(tasks.list(projectId, status, assigneeId, milestoneId, userId(jwt)));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<TaskView>> create(
            @PathVariable UUID projectId, @Valid @RequestBody CreateTaskRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        TaskView created = tasks.create(projectId, request, userId(jwt));
        return ResponseEntity.created(URI.create(
                "/api/v1/projects/" + projectId + "/tasks/" + created.id()))
                .body(ApiResponse.success(created));
    }

    @GetMapping("/{taskId}")
    public ApiResponse<TaskView> get(
            @PathVariable UUID projectId, @PathVariable UUID taskId,
            @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.success(tasks.get(projectId, taskId, userId(jwt)));
    }

    @PatchMapping("/{taskId}")
    public ApiResponse<TaskView> update(
            @PathVariable UUID projectId, @PathVariable UUID taskId,
            @Valid @RequestBody UpdateTaskRequest request, @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.success(tasks.update(projectId, taskId, request, userId(jwt)));
    }

    @DeleteMapping("/{taskId}")
    public ResponseEntity<Void> delete(
            @PathVariable UUID projectId, @PathVariable UUID taskId,
            @AuthenticationPrincipal Jwt jwt) {
        tasks.delete(projectId, taskId, userId(jwt));
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{taskId}/dependencies")
    public ApiResponse<TaskView> replaceDependencies(
            @PathVariable UUID projectId, @PathVariable UUID taskId,
            @Valid @RequestBody ReplaceDependenciesRequest request, @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.success(tasks.replaceDependencies(projectId, taskId, request, userId(jwt)));
    }

    @PostMapping("/batch")
    public ApiResponse<List<TaskView>> batchUpdate(
            @PathVariable UUID projectId,
            @Valid @RequestBody BatchUpdateTasksRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.success(tasks.batchUpdate(projectId, request, userId(jwt)));
    }

    private static UUID userId(Jwt jwt) {
        try { return UUID.fromString(jwt.getSubject()); }
        catch (RuntimeException exception) { throw new BusinessException(ErrorCode.AUTH_UNAUTHORIZED); }
    }
}
