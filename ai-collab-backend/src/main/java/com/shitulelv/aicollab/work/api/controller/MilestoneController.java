package com.shitulelv.aicollab.work.api.controller;

import com.shitulelv.aicollab.common.api.ApiResponse;
import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.work.api.dto.CreateMilestoneRequest;
import com.shitulelv.aicollab.work.api.dto.UpdateMilestoneRequest;
import com.shitulelv.aicollab.work.application.service.MilestoneApplicationService;
import com.shitulelv.aicollab.work.application.view.MilestoneView;
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
@RequestMapping("/api/v1/projects/{projectId}/milestones")
public class MilestoneController {
    private final MilestoneApplicationService milestones;
    public MilestoneController(MilestoneApplicationService milestones) { this.milestones = milestones; }

    @GetMapping
    public ApiResponse<List<MilestoneView>> list(
            @PathVariable UUID projectId, @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.success(milestones.list(projectId, userId(jwt)));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<MilestoneView>> create(
            @PathVariable UUID projectId, @Valid @RequestBody CreateMilestoneRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        MilestoneView created = milestones.create(projectId, request, userId(jwt));
        return ResponseEntity.created(URI.create(
                "/api/v1/projects/" + projectId + "/milestones/" + created.id()))
                .body(ApiResponse.success(created));
    }

    @PatchMapping("/{milestoneId}")
    public ApiResponse<MilestoneView> update(
            @PathVariable UUID projectId, @PathVariable UUID milestoneId,
            @Valid @RequestBody UpdateMilestoneRequest request, @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.success(milestones.update(projectId, milestoneId, request, userId(jwt)));
    }

    @DeleteMapping("/{milestoneId}")
    public ResponseEntity<Void> delete(
            @PathVariable UUID projectId, @PathVariable UUID milestoneId,
            @AuthenticationPrincipal Jwt jwt) {
        milestones.delete(projectId, milestoneId, userId(jwt));
        return ResponseEntity.noContent().build();
    }

    private static UUID userId(Jwt jwt) {
        try { return UUID.fromString(jwt.getSubject()); }
        catch (RuntimeException exception) { throw new BusinessException(ErrorCode.AUTH_UNAUTHORIZED); }
    }
}
