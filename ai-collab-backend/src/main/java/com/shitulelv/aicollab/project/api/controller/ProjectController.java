package com.shitulelv.aicollab.project.api.controller;

import com.shitulelv.aicollab.common.api.ApiResponse;
import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.project.api.dto.ChangeRoleRequest;
import com.shitulelv.aicollab.project.api.dto.CreateInvitationRequest;
import com.shitulelv.aicollab.project.api.dto.CreateProjectRequest;
import com.shitulelv.aicollab.project.api.dto.UpdateProjectRequest;
import com.shitulelv.aicollab.project.application.service.InvitationApplicationService;
import com.shitulelv.aicollab.project.application.service.ProjectApplicationService;
import com.shitulelv.aicollab.project.application.service.ProjectMemberApplicationService;
import com.shitulelv.aicollab.project.application.view.InvitationView;
import com.shitulelv.aicollab.project.application.view.MemberView;
import com.shitulelv.aicollab.project.application.view.ProjectView;
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
@RequestMapping("/api/v1/projects")
public class ProjectController {
    private final ProjectApplicationService projects;
    private final ProjectMemberApplicationService members;
    private final InvitationApplicationService invitations;

    public ProjectController(
            ProjectApplicationService projects,
            ProjectMemberApplicationService members,
            InvitationApplicationService invitations) {
        this.projects = projects;
        this.members = members;
        this.invitations = invitations;
    }

    @GetMapping
    public ApiResponse<List<ProjectView>> list(@AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.success(projects.list(userId(jwt)));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<ProjectView>> create(
            @Valid @RequestBody CreateProjectRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        ProjectView created = projects.create(request, userId(jwt));
        return ResponseEntity.created(URI.create("/api/v1/projects/" + created.id()))
                .body(ApiResponse.success(created));
    }

    @GetMapping("/{projectId}")
    public ApiResponse<ProjectView> get(
            @PathVariable UUID projectId,
            @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.success(projects.get(projectId, userId(jwt)));
    }

    @PatchMapping("/{projectId}")
    public ApiResponse<ProjectView> update(
            @PathVariable UUID projectId,
            @Valid @RequestBody UpdateProjectRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.success(projects.update(projectId, request, userId(jwt)));
    }

    @DeleteMapping("/{projectId}")
    public ResponseEntity<Void> delete(
            @PathVariable UUID projectId,
            @AuthenticationPrincipal Jwt jwt) {
        projects.delete(projectId, userId(jwt));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{projectId}/members")
    public ApiResponse<List<MemberView>> listMembers(
            @PathVariable UUID projectId,
            @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.success(members.list(projectId, userId(jwt)));
    }

    @PatchMapping("/{projectId}/members/{memberId}/role")
    public ApiResponse<MemberView> changeRole(
            @PathVariable UUID projectId,
            @PathVariable UUID memberId,
            @Valid @RequestBody ChangeRoleRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.success(members.changeRole(projectId, memberId, request.role(), userId(jwt)));
    }

    @DeleteMapping("/{projectId}/members/{memberId}")
    public ResponseEntity<Void> removeMember(
            @PathVariable UUID projectId,
            @PathVariable UUID memberId,
            @AuthenticationPrincipal Jwt jwt) {
        members.remove(projectId, memberId, userId(jwt));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{projectId}/invitations")
    public ResponseEntity<ApiResponse<InvitationView>> createInvitation(
            @PathVariable UUID projectId,
            @Valid @RequestBody CreateInvitationRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        InvitationView created = invitations.create(projectId, request, userId(jwt));
        return ResponseEntity.created(URI.create("/api/v1/invitations/" + created.code()))
                .body(ApiResponse.success(created));
    }

    private static UUID userId(Jwt jwt) {
        try {
            return UUID.fromString(jwt.getSubject());
        } catch (RuntimeException exception) {
            throw new BusinessException(ErrorCode.AUTH_UNAUTHORIZED);
        }
    }
}
