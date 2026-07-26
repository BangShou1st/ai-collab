package com.shitulelv.aicollab.document.api.controller;

import com.shitulelv.aicollab.common.api.ApiResponse;
import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.document.application.service.DocumentApplicationService;
import com.shitulelv.aicollab.document.application.view.DocumentView;
import com.shitulelv.aicollab.document.application.view.DownloadUrlView;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/documents")
public class DocumentController {
    private final DocumentApplicationService documents;
    public DocumentController(DocumentApplicationService documents) { this.documents = documents; }

    @GetMapping
    public ApiResponse<List<DocumentView>> list(
            @PathVariable UUID projectId, @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.success(documents.list(projectId, userId(jwt)));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<DocumentView>> upload(
            @PathVariable UUID projectId,
            @RequestPart("file") MultipartFile file,
            @RequestParam(required = false) String displayName,
            @AuthenticationPrincipal Jwt jwt) {
        DocumentView created = documents.upload(projectId, file, displayName, userId(jwt));
        return ResponseEntity.accepted().body(ApiResponse.success(created));
    }

    @GetMapping("/{documentId}")
    public ApiResponse<DocumentView> get(
            @PathVariable UUID projectId, @PathVariable UUID documentId,
            @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.success(documents.get(projectId, documentId, userId(jwt)));
    }

    @GetMapping("/{documentId}/download-url")
    public ApiResponse<DownloadUrlView> downloadUrl(
            @PathVariable UUID projectId, @PathVariable UUID documentId,
            @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.success(documents.downloadUrl(projectId, documentId, userId(jwt)));
    }

    @PostMapping("/{documentId}/retry")
    public ResponseEntity<ApiResponse<DocumentView>> retry(
            @PathVariable UUID projectId, @PathVariable UUID documentId,
            @AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.accepted()
                .body(ApiResponse.success(documents.retry(projectId, documentId, userId(jwt))));
    }

    @DeleteMapping("/{documentId}")
    public ResponseEntity<Void> delete(
            @PathVariable UUID projectId, @PathVariable UUID documentId,
            @AuthenticationPrincipal Jwt jwt) {
        documents.delete(projectId, documentId, userId(jwt));
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
