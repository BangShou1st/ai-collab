package com.shitulelv.aicollab.document.api.controller;

import com.shitulelv.aicollab.common.api.ApiResponse;
import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.document.application.service.BatchReindexService;
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
    private final BatchReindexService batchReindex;
    public DocumentController(DocumentApplicationService documents, BatchReindexService batchReindex) {
        this.documents = documents;
        this.batchReindex = batchReindex;
    }

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

    @PostMapping("/{documentId}/reindex")
    public ResponseEntity<ApiResponse<DocumentView>> reindex(
            @PathVariable UUID projectId, @PathVariable UUID documentId,
            @AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.accepted()
                .body(ApiResponse.success(documents.reindex(projectId, documentId, userId(jwt))));
    }

    @PostMapping("/reindex-all")
    public ResponseEntity<ApiResponse<java.util.Map<String, Integer>>> reindexAll(
            @PathVariable UUID projectId,
            @AuthenticationPrincipal Jwt jwt) {
        int queued = batchReindex.reindexAll(projectId, userId(jwt));
        return ResponseEntity.accepted()
                .body(ApiResponse.success(java.util.Map.of("queued", queued)));
    }

    @GetMapping("/reindex-progress")
    public ApiResponse<java.util.Map<String, Object>> reindexProgress(
            @PathVariable UUID projectId,
            @AuthenticationPrincipal Jwt jwt) {
        BatchReindexService.BatchProgress progress = batchReindex.getProgress(projectId, userId(jwt));
        return ApiResponse.success(java.util.Map.of(
                "total", progress.total,
                "completed", progress.getCompleted(),
                "failed", progress.getFailed(),
                "inProgress", progress.getInProgress()));
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
