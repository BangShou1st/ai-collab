package com.shitulelv.aicollab.connector.api.controller;

import com.shitulelv.aicollab.common.api.ApiResponse;
import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.connector.api.dto.CreateConnectionRequest;
import com.shitulelv.aicollab.connector.api.dto.ConfigureConnectorEventsRequest;
import com.shitulelv.aicollab.connector.application.ConnectorConnectionService;
import com.shitulelv.aicollab.connector.application.view.ConnectorViews.ConnectionView;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/connector/connections")
public class ConnectorConnectionController {
    private final ConnectorConnectionService connections;

    public ConnectorConnectionController(ConnectorConnectionService connections) {
        this.connections = connections;
    }

    @GetMapping
    public ApiResponse<List<ConnectionView>> list(@AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.success(connections.list(userId(jwt)));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<ConnectionView>> create(@Valid @RequestBody CreateConnectionRequest request,
                                                              @AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.created(java.net.URI.create("/api/v1/connector/connections"))
                .body(ApiResponse.success(connections.create(userId(jwt), request.projectId(),
                        request.name(), request.appId(), request.appSecret())));
    }

    @PostMapping("/{connectionId}/verify")
    public ApiResponse<ConnectionView> verify(@PathVariable UUID connectionId,
                                              @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.success(connections.verify(userId(jwt), connectionId));
    }

    @PutMapping("/{connectionId}/event-credentials")
    public ResponseEntity<Void> configureEvents(@PathVariable UUID connectionId,
            @Valid @RequestBody ConfigureConnectorEventsRequest request, @AuthenticationPrincipal Jwt jwt) {
        connections.configureEvents(userId(jwt), connectionId, request.encryptKey(), request.verificationToken());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{connectionId}")
    public ResponseEntity<Void> delete(@PathVariable UUID connectionId,
                                       @AuthenticationPrincipal Jwt jwt) {
        connections.delete(userId(jwt), connectionId);
        return ResponseEntity.noContent().build();
    }

    private static UUID userId(Jwt jwt) {
        try {
            return UUID.fromString(jwt.getSubject());
        } catch (RuntimeException exception) {
            throw new BusinessException(ErrorCode.AUTH_UNAUTHORIZED);
        }
    }
}
