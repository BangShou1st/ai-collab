package com.shitulelv.aicollab.project.api.controller;

import com.shitulelv.aicollab.auth.model.AuthenticationResult;
import com.shitulelv.aicollab.auth.service.RefreshTokenCookieService;
import com.shitulelv.aicollab.common.api.ApiResponse;
import com.shitulelv.aicollab.project.api.dto.AcceptInvitationRequest;
import com.shitulelv.aicollab.project.application.service.InvitationApplicationService;
import com.shitulelv.aicollab.project.application.view.InvitationPreview;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/invitations")
public class InvitationController {
    private final InvitationApplicationService invitations;
    private final RefreshTokenCookieService cookies;

    public InvitationController(
            InvitationApplicationService invitations,
            RefreshTokenCookieService cookies) {
        this.invitations = invitations;
        this.cookies = cookies;
    }

    @GetMapping("/{code}")
    public ApiResponse<InvitationPreview> preview(@PathVariable String code) {
        return ApiResponse.success(invitations.preview(code));
    }

    @PostMapping("/{code}/accept")
    public ResponseEntity<ApiResponse<com.shitulelv.aicollab.auth.dto.LoginResponse>> accept(
            @PathVariable String code,
            @Valid @RequestBody AcceptInvitationRequest request,
            HttpServletResponse response) {
        AuthenticationResult result = invitations.accept(code, request);
        response.addHeader(HttpHeaders.SET_COOKIE, cookies
                .createCookie(result.refreshToken().value(), result.refreshToken().expiresAt())
                .toString());
        return ResponseEntity.status(201).body(ApiResponse.success(result.loginResponse()));
    }
}
