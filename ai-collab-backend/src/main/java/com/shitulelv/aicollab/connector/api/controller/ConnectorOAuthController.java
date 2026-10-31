package com.shitulelv.aicollab.connector.api.controller;

import com.shitulelv.aicollab.common.api.ApiResponse;
import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.connector.application.ConnectorOAuthService;
import com.shitulelv.aicollab.connector.application.ConnectorConnectionService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class ConnectorOAuthController {
    private final ConnectorOAuthService oauth;
    private final ConnectorConnectionService connections;

    public ConnectorOAuthController(ConnectorOAuthService oauth, ConnectorConnectionService connections) {
        this.oauth = oauth;
        this.connections = connections;
    }

    @PostMapping("/projects/{projectId}/connector/connections/{connectionId}/oauth/start")
    public ApiResponse<ConnectorOAuthService.StartView> start(@PathVariable UUID projectId,
                                                               @PathVariable UUID connectionId,
                                                               @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.success(oauth.start(projectId, connectionId, userId(jwt)));
    }

    @GetMapping("/connector/oauth/callback")
    public ResponseEntity<Void> callback(@RequestParam String state, @RequestParam String code) {
        oauth.callback(state, code);
        return ResponseEntity.status(303).location(java.net.URI.create("/api/v1/connector/oauth/complete"))
                .cacheControl(CacheControl.noStore()).header("Referrer-Policy", "no-referrer").build();
    }

    @GetMapping("/connector/oauth/complete")
    public ResponseEntity<ApiResponse<String>> complete() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("Referrer-Policy", "no-referrer")
                .body(ApiResponse.success("授权完成，请返回项目页面刷新授权状态"));
    }

    @GetMapping("/projects/{projectId}/connector/authorizations")
    public ApiResponse<List<ConnectorOAuthService.GrantView>> list(@PathVariable UUID projectId,
                                                                   @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.success(oauth.list(projectId, userId(jwt)));
    }

    @GetMapping("/projects/{projectId}/connector/available-connections")
    public ApiResponse<List<ConnectorOAuthService.ConnectionOption>> availableConnections(
            @PathVariable UUID projectId, @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.success(oauth.availableConnections(projectId, userId(jwt)));
    }

    @GetMapping("/projects/{projectId}/connector/connections/{connectionId}/event-status")
    public ApiResponse<ConnectorConnectionService.EventStatusView> eventStatus(
            @PathVariable UUID projectId, @PathVariable UUID connectionId, @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.success(connections.eventStatus(projectId, userId(jwt), connectionId));
    }

    @DeleteMapping("/projects/{projectId}/connector/authorizations/{authorizationId}")
    public ResponseEntity<Void> disconnect(@PathVariable UUID projectId, @PathVariable UUID authorizationId,
                                           @AuthenticationPrincipal Jwt jwt) {
        oauth.disconnect(projectId, authorizationId, userId(jwt));
        return ResponseEntity.noContent().build();
    }

    private static UUID userId(Jwt jwt) {
        try { return UUID.fromString(jwt.getSubject()); }
        catch (RuntimeException failure) { throw new BusinessException(ErrorCode.AUTH_UNAUTHORIZED); }
    }
}
