package com.shitulelv.aicollab.connector.api.controller;

import com.shitulelv.aicollab.common.api.ApiResponse;
import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.connector.api.dto.CreateSourceRequest;
import com.shitulelv.aicollab.connector.application.ConnectorSourceService;
import com.shitulelv.aicollab.connector.application.view.ConnectorViews.ExternalDocumentView;
import com.shitulelv.aicollab.connector.application.view.ConnectorViews.SourceDocuments;
import com.shitulelv.aicollab.connector.application.view.ConnectorViews.SourceView;
import com.shitulelv.aicollab.connector.application.view.ConnectorViews.SyncAccepted;
import com.shitulelv.aicollab.connector.application.view.ConnectorViews.SyncJobView;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/connector/sources")
public class ConnectorSourceController {
    private final ConnectorSourceService sources;

    public ConnectorSourceController(ConnectorSourceService sources) {
        this.sources = sources;
    }

    @GetMapping
    public ApiResponse<List<SourceView>> list(@PathVariable UUID projectId,
                                              @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.success(sources.list(projectId, userId(jwt)));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<SourceView>> create(@PathVariable UUID projectId,
                                                          @Valid @RequestBody CreateSourceRequest request,
                                                          @AuthenticationPrincipal Jwt jwt) {
        SourceView view = sources.create(projectId, userId(jwt), request.connectionId(),
                request.folderToken(), request.name(), request.authorizationConfirmed(), request.userAuthorizationId());
        return ResponseEntity.created(URI.create("/api/v1/projects/" + projectId + "/connector/sources"))
                .body(ApiResponse.success(view));
    }

    @GetMapping("/{sourceId}/documents")
    public ApiResponse<SourceDocuments> documents(@PathVariable UUID projectId, @PathVariable UUID sourceId,
                                                  @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.success(new SourceDocuments(sources.documents(projectId, sourceId, userId(jwt))));
    }

    @PostMapping("/{sourceId}/sync")
    public ResponseEntity<ApiResponse<SyncAccepted>> sync(@PathVariable UUID projectId,
                                                          @PathVariable UUID sourceId,
                                                          @AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.accepted()
                .body(ApiResponse.success(sources.triggerSync(projectId, sourceId, userId(jwt), "MANUAL")));
    }

    @PostMapping("/{sourceId}/retry")
    public ResponseEntity<ApiResponse<SyncAccepted>> retry(@PathVariable UUID projectId,
                                                           @PathVariable UUID sourceId,
                                                           @AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.accepted()
                .body(ApiResponse.success(sources.triggerSync(projectId, sourceId, userId(jwt), "MANUAL")));
    }

    @GetMapping("/{sourceId}/status")
    public ApiResponse<SyncJobView> status(@PathVariable UUID projectId, @PathVariable UUID sourceId,
                                           @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.success(sources.latestJob(projectId, sourceId, userId(jwt)));
    }

    private static UUID userId(Jwt jwt) {
        try {
            return UUID.fromString(jwt.getSubject());
        } catch (RuntimeException exception) {
            throw new BusinessException(ErrorCode.AUTH_UNAUTHORIZED);
        }
    }
}
