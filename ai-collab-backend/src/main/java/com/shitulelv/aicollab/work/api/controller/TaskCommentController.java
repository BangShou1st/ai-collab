package com.shitulelv.aicollab.work.api.controller;

import com.shitulelv.aicollab.common.api.ApiResponse;
import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.work.api.dto.CreateCommentRequest;
import com.shitulelv.aicollab.work.api.dto.UpdateCommentRequest;
import com.shitulelv.aicollab.work.application.service.TaskCommentApplicationService;
import com.shitulelv.aicollab.work.application.view.TaskCommentView;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/tasks/{taskId}/comments")
public class TaskCommentController {
    private final TaskCommentApplicationService comments;
    public TaskCommentController(TaskCommentApplicationService comments) { this.comments = comments; }

    @GetMapping
    public ApiResponse<List<TaskCommentView>> list(
            @PathVariable UUID projectId, @PathVariable UUID taskId,
            @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.success(comments.list(projectId, taskId, userId(jwt)));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<TaskCommentView>> create(
            @PathVariable UUID projectId, @PathVariable UUID taskId,
            @Valid @RequestBody CreateCommentRequest request, @AuthenticationPrincipal Jwt jwt) {
        TaskCommentView created = comments.create(projectId, taskId, request, userId(jwt));
        return ResponseEntity.created(URI.create(
                "/api/v1/projects/" + projectId + "/tasks/" + taskId + "/comments/" + created.id()))
                .body(ApiResponse.success(created));
    }

    @PatchMapping("/{commentId}")
    public ApiResponse<TaskCommentView> update(
            @PathVariable UUID projectId, @PathVariable UUID taskId, @PathVariable UUID commentId,
            @Valid @RequestBody UpdateCommentRequest request, @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.success(comments.update(projectId, taskId, commentId, request, userId(jwt)));
    }

    @DeleteMapping("/{commentId}")
    public ResponseEntity<Void> delete(
            @PathVariable UUID projectId, @PathVariable UUID taskId, @PathVariable UUID commentId,
            @AuthenticationPrincipal Jwt jwt) {
        comments.delete(projectId, taskId, commentId, userId(jwt));
        return ResponseEntity.noContent().build();
    }

    private static UUID userId(Jwt jwt) {
        try { return UUID.fromString(jwt.getSubject()); }
        catch (RuntimeException exception) { throw new BusinessException(ErrorCode.AUTH_UNAUTHORIZED); }
    }
}
